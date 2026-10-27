package com.rokid.glass.mediastream.transport.webrtc

import android.media.AudioFormat
import com.rokid.glass.mediastream.capture.Nv21Frame
import com.rokid.glass.mediastream.capture.PcmFrame
import com.rokid.glass.mediastream.transport.signaling.IceCandidatePayload
import com.rokid.glass.mediastream.transport.webrtc.audio.AudioDeviceModuleConfig
import com.rokid.glass.mediastream.transport.webrtc.audio.PcmRingBuffer
import com.rokid.glass.mediastream.transport.webrtc.audio.WebRtcAudioAdapter
import com.rokid.glass.mediastream.transport.webrtc.video.VideoFrameTarget
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.Function1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcPublisherTest {
    @Test
    fun local_offer_listener_does_not_block_a_concurrent_remote_answer() {
        val factory = FakePublisherSessionFactory()
        val publisher = WebRtcPublisher(factory)
        val completed = CountDownLatch(1)
        var answeredWhileCallbackActive = false
        val listener = object : MediaPublisher.Listener by RecordingListener() {
            override fun onLocalOffer(sdp: String) {
                Thread {
                    publisher.setRemoteAnswer("answer")
                    completed.countDown()
                }.apply { isDaemon = true }.start()
                answeredWhileCallbackActive = completed.await(1, TimeUnit.SECONDS)
            }
        }
        publisher.prepare(PublishOptions(false, true), listener)
        publisher.createOffer()
        factory.sessions.single().completeOffer(Result.success("offer"))

        assertTrue("native callbacks must not hold the publisher lock while calling signaling", answeredWhileCallbackActive)
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals("answer", factory.sessions.single().remoteAnswer)
        publisher.close()
    }

    @Test
    fun concurrent_close_from_a_native_frame_callback_is_nonblocking_and_defers_native_disposal() {
        val releases = mutableListOf<String>()
        val factory = FakePublisherSessionFactory(releases)
        val publisher = WebRtcPublisher(factory)
        publisher.prepare(PublishOptions(true, false), RecordingListener())
        val completed = CountDownLatch(1)
        var closedWhileFrameActive = false
        var disposedWhileFrameActive = false
        factory.sessions.single().beforeVideoFrame = {
            Thread { publisher.close(); completed.countDown() }.apply { isDaemon = true }.start()
            closedWhileFrameActive = completed.await(1, TimeUnit.SECONDS)
            disposedWhileFrameActive = releases.isNotEmpty()
        }
        val frame = nv21Frame(ByteArray(12), 4, 2, 1L)
        publisher.onVideoFrame(frame)
        frame.close()

        assertTrue("close must not wait for a native callback to release the state lock", closedWhileFrameActive)
        assertFalse("native resources must survive an in-flight frame", disposedWhileFrameActive)
        assertEquals(listOf("peer", "videoSource", "audioAdapter", "audioModule", "factory"), releases)
    }

    @Test
    fun prepare_rejects_an_empty_media_selection() {
        val publisher = WebRtcPublisher(FakePublisherSessionFactory())

        assertThrows(IllegalArgumentException::class.java) {
            publisher.prepare(PublishOptions(videoEnabled = false, audioEnabled = false), RecordingListener())
        }
    }

    @Test
    fun every_supported_media_combination_attaches_only_requested_send_only_tracks() {
        val combinations = listOf(
            PublishOptions(videoEnabled = true, audioEnabled = true),
            PublishOptions(videoEnabled = true, audioEnabled = false),
            PublishOptions(videoEnabled = false, audioEnabled = true),
        )

        for (options in combinations) {
            val factory = FakePublisherSessionFactory()
            val publisher = WebRtcPublisher(factory)
            publisher.prepare(options, RecordingListener())
            val created = factory.sessions.single()

            assertEquals(options.videoEnabled, created.hasVideoSender)
            assertEquals(options.audioEnabled, created.hasAudioSender)
            assertFalse(created.audioConfig.recordEnabled)
            assertFalse(created.audioConfig.stereoInput)
            assertEquals(16_000, created.audioConfig.inputSampleRateHz)
            publisher.close()
        }
    }

    @Test
    fun external_nv21_and_pcm_frames_are_forwarded_only_to_enabled_adapters() {
        val factory = FakePublisherSessionFactory()
        val ringBuffer = PcmRingBuffer(capacityBytes = 320, bytesPerSecond = 32_000)
        val publisher = WebRtcPublisher(factory, ringBufferFactory = { ringBuffer })
        publisher.prepare(PublishOptions(videoEnabled = true, audioEnabled = true), RecordingListener())
        val session = factory.sessions.single()
        val video = nv21Frame(ByteArray(12) { it.toByte() }, width = 4, height = 2, timestampNs = 77L)

        publisher.onVideoFrame(video)
        publisher.onAudioFrame(pcmFrame(byteArrayOf(1, 2, 3, 4), 88L))
        val audio = ByteBuffer.allocateDirect(4)
        val captureTimeNs = session.audioAdapter.onBuffer(
            audio,
            AudioFormat.ENCODING_PCM_16BIT,
            1,
            16_000,
            4,
            0L,
        )
        video.close()

        assertEquals(listOf(VideoObservation(4, 2, 77L)), session.videoFrames)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.bytes())
        assertEquals(88L, captureTimeNs)
    }

    @Test
    fun local_ice_is_published_only_after_the_local_offer() {
        val factory = FakePublisherSessionFactory()
        val listener = RecordingListener()
        val publisher = WebRtcPublisher(factory)
        publisher.prepare(PublishOptions(videoEnabled = true, audioEnabled = false), listener)
        val session = factory.sessions.single()
        val first = candidate("candidate:first")
        val second = candidate("candidate:second")

        publisher.createOffer()
        session.events.onLocalIceCandidate(first)
        assertTrue(listener.events.isEmpty())
        session.completeOffer(Result.success("v=0\r\na=sendonly\r\n"))
        session.events.onLocalIceCandidate(second)

        assertEquals(listOf("offer:v=0\r\na=sendonly\r\n", "ice:candidate:first", "ice:candidate:second"), listener.events)
    }

    @Test
    fun remote_ice_is_cached_until_answer_succeeds_and_add_failures_are_reported() {
        val factory = FakePublisherSessionFactory()
        val listener = RecordingListener()
        val publisher = WebRtcPublisher(factory)
        publisher.prepare(PublishOptions(videoEnabled = false, audioEnabled = true), listener)
        val session = factory.sessions.single()
        publisher.createOffer()
        session.completeOffer(Result.success("offer"))
        val early = candidate("candidate:early")
        val late = candidate("candidate:late")

        publisher.addRemoteIceCandidate(early)
        assertTrue(session.remoteCandidates.isEmpty())
        publisher.setRemoteAnswer("answer")
        assertEquals("answer", session.remoteAnswer)
        session.completeAnswer(Result.success(Unit))
        assertEquals(listOf(early), session.remoteCandidates)
        session.acceptRemoteCandidate = false
        publisher.addRemoteIceCandidate(late)

        assertEquals(1, listener.failures.size)
        assertTrue(listener.failures.single().first.contains("WEBRTC_NEGOTIATION_FAILED"))
    }

    @Test
    fun remote_ice_arriving_during_cached_drain_keeps_fifo_order() {
        val factory = FakePublisherSessionFactory()
        val publisher = WebRtcPublisher(factory)
        publisher.prepare(PublishOptions(videoEnabled = false, audioEnabled = true), RecordingListener())
        val session = factory.sessions.single()
        val first = candidate("candidate:first")
        val second = candidate("candidate:second")
        val duringDrain = candidate("candidate:during-drain")
        publisher.createOffer()
        session.completeOffer(Result.success("offer"))
        publisher.addRemoteIceCandidate(first)
        publisher.addRemoteIceCandidate(second)
        publisher.setRemoteAnswer("answer")
        session.beforeRemoteCandidateResult = { candidate ->
            if (candidate == first) {
                session.beforeRemoteCandidateResult = null
                publisher.addRemoteIceCandidate(duringDrain)
            }
        }

        session.completeAnswer(Result.success(Unit))

        assertEquals(listOf(first, second, duringDrain), session.remoteCandidates)
    }

    @Test
    fun failure_from_an_old_session_candidate_is_not_reported_to_a_new_listener() {
        val factory = FakePublisherSessionFactory()
        val firstListener = RecordingListener()
        val secondListener = RecordingListener()
        val publisher = WebRtcPublisher(factory)
        publisher.prepare(PublishOptions(videoEnabled = false, audioEnabled = true), firstListener)
        val oldSession = factory.sessions.single()
        publisher.createOffer()
        oldSession.completeOffer(Result.success("offer"))
        publisher.setRemoteAnswer("answer")
        oldSession.completeAnswer(Result.success(Unit))
        oldSession.acceptRemoteCandidate = false
        oldSession.beforeRemoteCandidateResult = {
            oldSession.beforeRemoteCandidateResult = null
            publisher.close()
            publisher.prepare(PublishOptions(videoEnabled = true, audioEnabled = false), secondListener)
        }

        publisher.addRemoteIceCandidate(candidate("candidate:old"))

        assertTrue(firstListener.failures.isEmpty())
        assertTrue(secondListener.failures.isEmpty())
        assertEquals(2, factory.sessions.size)
    }

    @Test
    fun invalid_or_duplicate_negotiation_operations_fail_without_reaching_the_peer() {
        val factory = FakePublisherSessionFactory()
        val listener = RecordingListener()
        val publisher = WebRtcPublisher(factory)
        publisher.prepare(PublishOptions(videoEnabled = true, audioEnabled = false), listener)
        val session = factory.sessions.single()

        publisher.setRemoteAnswer("too early")
        publisher.createOffer()
        publisher.createOffer()
        session.completeOffer(Result.success("offer"))
        publisher.setRemoteAnswer("answer")
        publisher.setRemoteAnswer("duplicate")

        assertEquals(1, session.createOfferCount)
        assertEquals("answer", session.remoteAnswer)
        assertEquals(3, listener.failures.size)
        assertTrue(listener.failures.all { it.first.contains("WEBRTC_NEGOTIATION_FAILED") })
    }

    @Test
    fun connection_failures_map_before_and_after_streaming() {
        val negotiatingFactory = FakePublisherSessionFactory()
        val negotiatingListener = RecordingListener()
        val negotiating = WebRtcPublisher(negotiatingFactory)
        negotiating.prepare(PublishOptions(true, false), negotiatingListener)
        negotiatingFactory.sessions.single().events.onConnectionStateChanged(PublisherConnectionState.FAILED)

        assertTrue(negotiatingListener.failures.single().first.contains("WEBRTC_NEGOTIATION_FAILED"))

        val streamingFactory = FakePublisherSessionFactory()
        val streamingListener = RecordingListener()
        val streaming = WebRtcPublisher(streamingFactory)
        streaming.prepare(PublishOptions(false, true), streamingListener)
        val session = streamingFactory.sessions.single()
        session.events.onConnectionStateChanged(PublisherConnectionState.CONNECTED)
        session.events.onConnectionStateChanged(PublisherConnectionState.CONNECTED)
        session.events.onConnectionStateChanged(PublisherConnectionState.DISCONNECTED)
        session.events.onConnectionStateChanged(PublisherConnectionState.FAILED)

        assertEquals(1, streamingListener.connectedCount)
        assertEquals(1, streamingListener.disconnectedReasons.size)
        assertTrue(streamingListener.disconnectedReasons.single().contains("NETWORK_DISCONNECTED"))
        assertTrue(streamingListener.failures.isEmpty())
    }

    @Test
    fun stats_include_external_pcm_buffer_metrics() {
        val factory = FakePublisherSessionFactory()
        val ringBuffer = PcmRingBuffer(capacityBytes = 4, bytesPerSecond = 32_000)
        val listener = RecordingListener()
        val publisher = WebRtcPublisher(factory, ringBufferFactory = { ringBuffer })
        publisher.prepare(PublishOptions(videoEnabled = false, audioEnabled = true), listener)
        val session = factory.sessions.single()
        publisher.onAudioFrame(pcmFrame(byteArrayOf(1, 2, 3, 4, 5, 6), 100L))
        session.audioAdapter.onBuffer(
            ByteBuffer.allocateDirect(8),
            AudioFormat.ENCODING_PCM_16BIT,
            1,
            16_000,
            8,
            0L,
        )

        session.events.onStats(TransportStats(videoBitrateBps = 8_000, audioBitrateBps = 4_000))

        assertEquals(2L, listener.stats.single().pcmDroppedBytes)
        assertEquals(4L, listener.stats.single().pcmUnderrunBytes)
        assertEquals(8_000L, listener.stats.single().videoBitrateBps)
        assertEquals(4_000L, listener.stats.single().audioBitrateBps)
    }

    @Test
    fun close_releases_every_resource_once_and_ignores_old_generation_callbacks() {
        val releases = mutableListOf<String>()
        val factory = FakePublisherSessionFactory(releases)
        val firstListener = RecordingListener()
        val publisher = WebRtcPublisher(factory)
        publisher.prepare(PublishOptions(true, true), firstListener)
        val oldSession = factory.sessions.single()

        publisher.close()
        publisher.close()
        oldSession.events.onConnectionStateChanged(PublisherConnectionState.CONNECTED)
        oldSession.events.onStats(TransportStats(videoBytesSent = 99))
        oldSession.events.onLocalIceCandidate(candidate("candidate:old"))
        oldSession.completeOffer(Result.success("old offer"))

        assertEquals(listOf("peer", "videoSource", "audioAdapter", "audioModule", "factory"), releases)
        assertEquals(0, firstListener.connectedCount)
        assertTrue(firstListener.stats.isEmpty())
        assertTrue(firstListener.events.isEmpty())

        val secondListener = RecordingListener()
        publisher.prepare(PublishOptions(true, false), secondListener)
        factory.sessions.last().events.onConnectionStateChanged(PublisherConnectionState.CONNECTED)
        assertEquals(1, secondListener.connectedCount)
    }

    private class FakePublisherSessionFactory(
        private val releases: MutableList<String> = mutableListOf(),
    ) : PublisherSessionFactory {
        val sessions = mutableListOf<FakePublisherSession>()

        override fun create(
            options: PublishOptions,
            audioAdapter: WebRtcAudioAdapter,
            audioConfig: AudioDeviceModuleConfig,
            events: PublisherSessionEvents,
        ): PublisherSession = FakePublisherSession(options, audioAdapter, audioConfig, events, releases)
            .also(sessions::add)
    }

    private class FakePublisherSession(
        options: PublishOptions,
        val audioAdapter: WebRtcAudioAdapter,
        val audioConfig: AudioDeviceModuleConfig,
        val events: PublisherSessionEvents,
        private val releases: MutableList<String>,
    ) : PublisherSession {
        val hasVideoSender = options.videoEnabled
        val hasAudioSender = options.audioEnabled
        val videoFrames = mutableListOf<VideoObservation>()
        var beforeVideoFrame: (() -> Unit)? = null
        override val videoFrameTarget: VideoFrameTarget? = if (options.videoEnabled) {
            VideoFrameTarget { frame ->
                beforeVideoFrame?.invoke()
                videoFrames += VideoObservation(frame.buffer.width, frame.buffer.height, frame.timestampNs)
            }
        } else {
            null
        }
        var createOfferCount = 0
        var offerResult: ((Result<String>) -> Unit)? = null
        var remoteAnswer: String? = null
        var answerResult: ((Result<Unit>) -> Unit)? = null
        val remoteCandidates = mutableListOf<IceCandidatePayload>()
        var acceptRemoteCandidate = true
        var beforeRemoteCandidateResult: ((IceCandidatePayload) -> Unit)? = null
        private var closed = false

        override fun createOffer(onResult: (Result<String>) -> Unit) {
            createOfferCount += 1
            offerResult = onResult
        }

        override fun setRemoteAnswer(sdp: String, onResult: (Result<Unit>) -> Unit) {
            remoteAnswer = sdp
            answerResult = onResult
        }

        override fun addRemoteIceCandidate(candidate: IceCandidatePayload): Boolean {
            remoteCandidates += candidate
            beforeRemoteCandidateResult?.invoke(candidate)
            return acceptRemoteCandidate
        }

        override fun close(releaseAudioAdapter: () -> Unit) {
            if (closed) return
            closed = true
            releases += "peer"
            if (hasVideoSender) releases += "videoSource"
            releaseAudioAdapter()
            releases += "audioAdapter"
            releases += "audioModule"
            releases += "factory"
        }

        fun completeOffer(result: Result<String>) {
            offerResult?.invoke(result)
        }

        fun completeAnswer(result: Result<Unit>) {
            answerResult?.invoke(result)
        }
    }

    private class RecordingListener : MediaPublisher.Listener {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, Throwable?>>()
        val stats = mutableListOf<TransportStats>()
        val disconnectedReasons = mutableListOf<String>()
        var connectedCount = 0

        override fun onLocalOffer(sdp: String) {
            events += "offer:$sdp"
        }

        override fun onLocalIceCandidate(candidate: IceCandidatePayload) {
            events += "ice:${candidate.candidate}"
        }

        override fun onConnected() {
            connectedCount += 1
        }

        override fun onDisconnected(reason: String) {
            disconnectedReasons += reason
        }

        override fun onFailure(message: String, cause: Throwable?) {
            failures += message to cause
        }

        override fun onStats(stats: TransportStats) {
            this.stats += stats
        }
    }

    private data class VideoObservation(val width: Int, val height: Int, val timestampNs: Long)

    private fun candidate(value: String) = IceCandidatePayload("0", 0, value)

    private fun pcmFrame(data: ByteArray, timestampNs: Long) = PcmFrame(data, 16_000, 1, 16, timestampNs)

    private fun nv21Frame(data: ByteArray, width: Int, height: Int, timestampNs: Long): Nv21Frame {
        val companion = Nv21Frame::class.java.getField("Companion").get(null)
        val create = companion.javaClass.getDeclaredMethod(
            "create",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Function1::class.java,
        )
        return try {
            create.invoke(companion, data, width, height, timestampNs, { _: ByteArray -> Unit }) as Nv21Frame
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun ByteBuffer.bytes(): ByteArray = ByteArray(capacity()).also { output ->
        duplicate().apply {
            position(0)
            get(output)
        }
    }
}
