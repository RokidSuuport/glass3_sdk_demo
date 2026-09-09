package com.rokid.glass.mediastream.transport.webrtc

import livekit.org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDirectionPolicyTest {
    @Test
    fun local_audio_and_video_are_strictly_send_only() {
        assertEquals(RtpTransceiverDirection.SEND_ONLY, MediaDirectionPolicy.directionFor(LocalMediaKind.AUDIO))
        assertEquals(RtpTransceiverDirection.SEND_ONLY, MediaDirectionPolicy.directionFor(LocalMediaKind.VIDEO))
    }

    @Test
    fun lan_profile_has_no_external_ice_servers() {
        assertTrue(MediaDirectionPolicy.lanIceServers.isEmpty())
    }

    @Test
    fun remote_media_is_rejected_on_the_sender_endpoint() {
        assertThrows(IllegalStateException::class.java) {
            MediaDirectionPolicy.rejectRemoteTrack(LocalMediaKind.AUDIO)
        }
        assertThrows(IllegalStateException::class.java) {
            MediaDirectionPolicy.rejectRemoteTrack(LocalMediaKind.VIDEO)
        }
    }
}
