package com.rokid.phone.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStreamRecoveryPolicyTest {
    private val policy = VideoStreamRecoveryPolicy(stallTimeoutMs = 5_000L)

    @Test
    fun `bluetooth disconnect only reports bluetooth state`() {
        assertEquals(
            VideoStreamRecoveryPolicy.Action.REPORT_BLUETOOTH_DISCONNECTED,
            policy.decide(
                bluetoothConnected = false,
                p2pConnected = true,
                isH264Mode = true,
                hasReceivedPacket = false,
                lastPacketAgeMs = 9_000L,
                lastRenderedFrameAgeMs = 9_000L,
                remoteRestartAvailable = true,
                localDecoderRestartAvailable = true,
            )
        )
    }

    @Test
    fun `p2p disconnect does not restart remote camera`() {
        assertEquals(
            VideoStreamRecoveryPolicy.Action.REPORT_P2P_DISCONNECTED,
            policy.decide(
                bluetoothConnected = true,
                p2pConnected = false,
                isH264Mode = true,
                hasReceivedPacket = false,
                lastPacketAgeMs = 9_000L,
                lastRenderedFrameAgeMs = 9_000L,
                remoteRestartAvailable = true,
                localDecoderRestartAvailable = true,
            )
        )
    }

    @Test
    fun `incoming h264 packets without rendered frames restart only local decoder`() {
        assertEquals(
            VideoStreamRecoveryPolicy.Action.RESTART_LOCAL_DECODER,
            policy.decide(
                bluetoothConnected = true,
                p2pConnected = true,
                isH264Mode = true,
                hasReceivedPacket = true,
                lastPacketAgeMs = 200L,
                lastRenderedFrameAgeMs = 6_000L,
                remoteRestartAvailable = true,
                localDecoderRestartAvailable = true,
            )
        )
    }

    @Test
    fun `connected channels without video packets restart remote stream once`() {
        assertEquals(
            VideoStreamRecoveryPolicy.Action.RESTART_REMOTE_STREAM,
            policy.decide(
                bluetoothConnected = true,
                p2pConnected = true,
                isH264Mode = false,
                hasReceivedPacket = false,
                lastPacketAgeMs = 9_000L,
                lastRenderedFrameAgeMs = 9_000L,
                remoteRestartAvailable = true,
                localDecoderRestartAvailable = true,
            )
        )
    }

    @Test
    fun `exhausted recovery budget reports no data instead of restarting repeatedly`() {
        assertEquals(
            VideoStreamRecoveryPolicy.Action.REPORT_NO_DATA,
            policy.decide(
                bluetoothConnected = true,
                p2pConnected = true,
                isH264Mode = false,
                hasReceivedPacket = false,
                lastPacketAgeMs = 9_000L,
                lastRenderedFrameAgeMs = 9_000L,
                remoteRestartAvailable = false,
                localDecoderRestartAvailable = false,
            )
        )
    }
}
