package com.rokid.glass.mediastream.transport.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePublisherResourceLifecycleTest {
    @Test
    fun video_source_lifecycle_notifies_start_and_stop_exactly_once() {
        val events = mutableListOf<String>()
        val lifecycle = NativeVideoSourceLifecycle(
            onStarted = { success -> events += "started:$success" },
            onStopped = { events += "stopped" },
        )

        lifecycle.start()
        lifecycle.start()
        lifecycle.stop()
        lifecycle.stop()

        assertEquals(listOf("started:true", "stopped"), events)
    }

    @Test
    fun native_release_path_stops_video_and_disposes_peer_exactly_once() {
        val events = mutableListOf<String>()
        val releaser = NativePublisherResourceReleaser(
            NativePublisherReleaseActions(
                closeEventGate = { events += "eventGate" },
                stopStats = { events += "stats" },
                disableAudioRecording = { events += "audioRecording" },
                disableAudioPlayout = { events += "audioPlayout" },
                stopVideoSource = { events += "videoStopped" },
                disposePeer = { events += "peerDisposed" },
                disposeVideoTrack = { events += "videoTrack" },
                disposeAudioTrack = { events += "audioTrack" },
                disposeVideoSource = { events += "videoSource" },
                disposeAudioSource = { events += "audioSource" },
                releaseAudioDeviceModule = { events += "audioModule" },
                disposePeerFactory = { events += "factory" },
                releaseEgl = { events += "egl" },
            ),
        )

        releaser.release { events += "audioAdapter" }
        releaser.release { events += "duplicateAudioAdapter" }

        assertEquals(
            listOf(
                "eventGate",
                "stats",
                "audioRecording",
                "audioPlayout",
                "videoStopped",
                "peerDisposed",
                "videoTrack",
                "audioTrack",
                "videoSource",
                "audioSource",
                "audioAdapter",
                "audioModule",
                "factory",
                "egl",
            ),
            events,
        )
        assertEquals(1, events.count { it == "peerDisposed" })
    }

    @Test
    fun native_release_continues_after_an_individual_action_fails() {
        val events = mutableListOf<String>()
        val releaser = NativePublisherResourceReleaser(
            NativePublisherReleaseActions(
                stopVideoSource = {
                    events += "videoStopped"
                    error("stop failed")
                },
                disposePeer = { events += "peerDisposed" },
                releaseAudioDeviceModule = { events += "audioModule" },
            ),
        )

        releaser.release { events += "audioAdapter" }

        assertEquals(
            listOf("videoStopped", "peerDisposed", "audioAdapter", "audioModule"),
            events,
        )
    }
}
