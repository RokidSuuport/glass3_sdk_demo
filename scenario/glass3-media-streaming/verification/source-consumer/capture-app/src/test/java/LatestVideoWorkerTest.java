import com.rokid.glass.mediastream.guide.common.LatestVideoWorker;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LatestVideoWorkerTest {
    @Test public void slowConsumerKeepsOnlyNewestPendingFrame() throws Exception {
        try (LatestVideoWorker worker = new LatestVideoWorker()) {
            long token = worker.start();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            CountDownLatch newest = new CountDownLatch(1);
            AtomicInteger obsolete = new AtomicInteger();
            worker.offer(token, () -> { entered.countDown(); await(unblock); return "first"; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) worker.offer(token, () -> { obsolete.incrementAndGet(); return "old"; });
            worker.offer(token, () -> { newest.countDown(); return "newest"; });
            unblock.countDown();
            assertTrue(newest.await(2, TimeUnit.SECONDS));
            assertEquals("慢任务期间只能保留最后一帧", 0, obsolete.get());
        }
    }

    @Test public void stoppedSessionCannotPublishIntoRestartedSession() throws Exception {
        try (LatestVideoWorker worker = new LatestVideoWorker()) {
            long token = worker.start();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            CountDownLatch next = new CountDownLatch(1);
            worker.offer(token, () -> { entered.countDown(); await(unblock); return "stale"; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            worker.stop();
            long restarted = worker.start();
            worker.publishAudio(token, "stale audio");
            assertEquals("", worker.latestAudio());
            worker.offer(token, () -> { fail("迟到回调不可进入新会话"); return "old callback"; });
            worker.offer(restarted, () -> {
                assertEquals("旧任务完成不能覆盖新会话", "", worker.latest());
                next.countDown();
                return "fresh";
            });
            unblock.countDown();
            assertTrue(next.await(2, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
