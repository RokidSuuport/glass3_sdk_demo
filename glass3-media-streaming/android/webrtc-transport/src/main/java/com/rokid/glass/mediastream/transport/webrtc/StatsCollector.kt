package com.rokid.glass.mediastream.transport.webrtc

import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RTCStatsReport

internal class StatsAccumulator {
    private var previousTimeMs: Long? = null
    private var previousVideoBytes = 0L
    private var previousAudioBytes = 0L

    @Synchronized
    fun calculate(nowMs: Long, stats: List<RawRtcStat>): TransportStats {
        var videoBytes = 0L
        var audioBytes = 0L
        var framesSent = 0L
        var framesEncoded = 0L
        var packetsLost = 0L
        var roundTripTimeMs = 0L

        for (stat in stats) {
            val kind = stat.members["kind"]?.toString()
                ?: stat.members["mediaType"]?.toString()
            when {
                stat.type == "outbound-rtp" && kind == "video" -> {
                    videoBytes = saturatingAdd(videoBytes, stat.members.nonNegativeLong("bytesSent"))
                    framesSent = saturatingAdd(framesSent, stat.members.nonNegativeLong("framesSent"))
                    framesEncoded = saturatingAdd(
                        framesEncoded,
                        stat.members.nonNegativeLong("framesEncoded"),
                    )
                }

                stat.type == "outbound-rtp" && kind == "audio" -> {
                    audioBytes = saturatingAdd(audioBytes, stat.members.nonNegativeLong("bytesSent"))
                }

                stat.type == "remote-inbound-rtp" -> {
                    packetsLost = saturatingAdd(packetsLost, stat.members.longValue("packetsLost"))
                    val seconds = stat.members.doubleValue("roundTripTime").coerceAtLeast(0.0)
                    val rttMs = (seconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0)
                    roundTripTimeMs = maxOf(roundTripTimeMs, rttMs)
                }
            }
        }

        val elapsedMs = previousTimeMs?.let { nowMs - it } ?: 0L
        val videoBitrate = bitrate(videoBytes, previousVideoBytes, elapsedMs)
        val audioBitrate = bitrate(audioBytes, previousAudioBytes, elapsedMs)
        previousTimeMs = nowMs
        previousVideoBytes = videoBytes
        previousAudioBytes = audioBytes

        return TransportStats(
            videoBytesSent = videoBytes,
            audioBytesSent = audioBytes,
            videoBitrateBps = videoBitrate,
            audioBitrateBps = audioBitrate,
            framesSent = framesSent,
            framesEncoded = framesEncoded,
            packetsLost = packetsLost.coerceAtLeast(0),
            roundTripTimeMs = roundTripTimeMs,
        )
    }

    private fun bitrate(currentBytes: Long, previousBytes: Long, elapsedMs: Long): Long {
        val deltaBytes = currentBytes - previousBytes
        if (elapsedMs <= 0 || deltaBytes < 0) return 0
        return runCatching {
            Math.multiplyExact(
                Math.multiplyExact(deltaBytes, BITS_PER_BYTE),
                MILLIS_PER_SECOND,
            ) / elapsedMs
        }.getOrDefault(Long.MAX_VALUE)
    }

    private fun Map<String, Any>.nonNegativeLong(key: String): Long =
        longValue(key).coerceAtLeast(0)

    private fun Map<String, Any>.longValue(key: String): Long = when (val value = this[key]) {
        is BigInteger -> value.coerceIn(LONG_MIN, LONG_MAX).toLong()
        is Number -> value.toLong()
        else -> 0L
    }

    private fun Map<String, Any>.doubleValue(key: String): Double = when (val value = this[key]) {
        is BigInteger -> value.toDouble()
        is Number -> value.toDouble()
        else -> 0.0
    }

    private fun saturatingAdd(left: Long, right: Long): Long = runCatching {
        Math.addExact(left, right)
    }.getOrElse {
        if (right >= 0) Long.MAX_VALUE else Long.MIN_VALUE
    }

    companion object {
        private const val BITS_PER_BYTE = 8L
        private const val MILLIS_PER_SECOND = 1_000L
        private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    }
}

internal class StatsCollector(
    private val onStats: (TransportStats) -> Unit,
    private val elapsedRealtimeMs: () -> Long,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "webrtc-stats").apply { isDaemon = true }
    },
) {
    private val accumulator = StatsAccumulator()
    @Volatile private var peerConnection: PeerConnection? = null

    fun start(peerConnection: PeerConnection) {
        check(this.peerConnection == null) { "StatsCollector is already started" }
        this.peerConnection = peerConnection
        executor.scheduleWithFixedDelay(::collect, 0, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        peerConnection = null
        executor.shutdownNow()
    }

    private fun collect() {
        val peer = peerConnection ?: return
        peer.getStats { report ->
            if (peerConnection !== peer) return@getStats
            onStats(accumulator.calculate(elapsedRealtimeMs(), report.toRawStats()))
        }
    }

    private fun RTCStatsReport.toRawStats(): List<RawRtcStat> =
        statsMap.values.map { stat -> RawRtcStat(stat.type, stat.members) }
}
