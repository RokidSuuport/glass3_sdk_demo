package com.rokid.glass.mediastream.guide.common;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 完整页面示例的私有工作队列，随 MediaCaptureActivity 一起复制。
 * 只用于允许丢弃旧帧的实时算法/预览；连续录像、PCM 音频不能套用此丢帧策略。
 * 任务闭包只持有 copyData() 得到的自有数组，丢弃任务时无需归还 SDK 租约。
 */
public final class LatestVideoWorker implements AutoCloseable {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
        runnable -> new Thread(runnable, "glass3-video-consumer"),
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private long generation;
    private boolean active;
    private String latest = "";
    private String audioSummary = "";

    public synchronized long start() {
        generation++;
        active = true;
        latest = "";
        audioSummary = "";
        executor.getQueue().clear();
        return generation;
    }

    public synchronized void offer(long token, Supplier<String> task) {
        if (!active || token != generation || executor.isShutdown()) return;
        executor.execute(() -> {
            synchronized (this) {
                if (!active || token != generation) return;
            }
            String result;
            try {
                result = task.get();
            } catch (RuntimeException error) {
                result = "视频处理失败：" + error.getMessage();
            }
            synchronized (this) {
                // 已开始执行的业务任务不能强制中止，但不能更新下一次采集的结果。
                if (active && token == generation) latest = result;
            }
        });
    }

    public synchronized String latest() { return latest; }

    // 这里只保存 PCM 元数据供页面采样显示，不缓存或丢弃客户的原始音频流。
    public synchronized void publishAudio(long token, String text) {
        if (active && token == generation) audioSummary = text;
    }
    public synchronized String latestAudio() { return audioSummary; }

    public synchronized void stop() {
        active = false;
        generation++;
        latest = "";
        audioSummary = "";
        executor.getQueue().clear();
    }

    @Override public synchronized void close() {
        stop();
        executor.shutdownNow();
    }
}
