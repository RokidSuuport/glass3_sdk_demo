package com.rokid.glass.mediastream.sender.capture

import com.rokid.glass.mediastream.capture.PcmFrame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavRecorderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun known_pcm_writes_the_exact_little_endian_header_and_preserves_append_order() {
        val output = outputFile("ordered.wav")
        val recorder = WavRecorder()
        val first = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val second = byteArrayOf(0x05, 0x06)

        recorder.start(output, PCM_16K_MONO)
        assertTrue(recorder.append(frame(first)))
        assertTrue(recorder.append(frame(second)))
        assertEquals(output.canonicalFile, recorder.stop()?.canonicalFile)

        val bytes = output.readBytes()
        assertEquals(50, bytes.size)
        assertEquals("RIFF", ascii(bytes, 0, 4))
        assertEquals(42, int32(bytes, 4))
        assertEquals("WAVE", ascii(bytes, 8, 4))
        assertEquals("fmt ", ascii(bytes, 12, 4))
        assertEquals(16, int32(bytes, 16))
        assertEquals(1, int16(bytes, 20))
        assertEquals(1, int16(bytes, 22))
        assertEquals(16_000, int32(bytes, 24))
        assertEquals(32_000, int32(bytes, 28))
        assertEquals(2, int16(bytes, 32))
        assertEquals(16, int16(bytes, 34))
        assertEquals("data", ascii(bytes, 36, 4))
        assertEquals(6, int32(bytes, 40))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), bytes.copyOfRange(44, bytes.size))

        recorder.close()
    }

    @Test
    fun riff_and_data_lengths_match_the_final_file_size() {
        val output = outputFile("lengths.wav")
        val recorder = WavRecorder()
        val pcm = byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)

        recorder.start(output, PCM_16K_MONO)
        assertTrue(recorder.append(frame(pcm)))
        assertNotNull(recorder.stop())

        val bytes = output.readBytes()
        assertEquals(bytes.size - 8, int32(bytes, 4))
        assertEquals(pcm.size, int32(bytes, 40))
        recorder.close()
    }

    @Test
    fun repeated_stop_is_idempotent_and_does_not_patch_or_append_twice() {
        val output = outputFile("idempotent-stop.wav")
        val recorder = WavRecorder()

        recorder.start(output, PCM_16K_MONO)
        assertTrue(recorder.append(frame(byteArrayOf(1, 2, 3, 4))))
        val firstResult = recorder.stop()
        val bytesAfterFirstStop = output.readBytes()

        val secondResult = recorder.stop()

        assertEquals(firstResult?.canonicalFile, secondResult?.canonicalFile)
        assertArrayEquals(bytesAfterFirstStop, output.readBytes())
        recorder.close()
    }

    @Test
    fun append_after_stop_is_ignored_and_never_changes_the_file() {
        val output = outputFile("append-after-stop.wav")
        val recorder = WavRecorder()

        recorder.start(output, PCM_16K_MONO)
        assertTrue(recorder.append(frame(byteArrayOf(1, 2))))
        assertNotNull(recorder.stop())
        val bytesAfterStop = output.readBytes()

        assertFalse(recorder.append(frame(byteArrayOf(3, 4))))
        assertArrayEquals(bytesAfterStop, output.readBytes())
        recorder.close()
    }

    @Test
    fun sample_rate_channel_count_or_bit_depth_change_is_rejected() {
        data class ChangedFormat(
            val name: String,
            val sampleRateHz: Int = 16_000,
            val channelCount: Int = 1,
            val bitsPerSample: Int = 16,
            val pcm: ByteArray = byteArrayOf(3, 4),
        )

        val changes = listOf(
            ChangedFormat(name = "sample-rate", sampleRateHz = 48_000),
            ChangedFormat(name = "channels", channelCount = 2, pcm = byteArrayOf(3, 4, 5, 6)),
            ChangedFormat(name = "bit-depth", bitsPerSample = 24, pcm = byteArrayOf(3, 4, 5)),
        )

        changes.forEach { changed ->
            val output = outputFile("format-${changed.name}.wav")
            val recorder = WavRecorder()
            recorder.start(output, PCM_16K_MONO)
            assertTrue(recorder.append(frame(byteArrayOf(1, 2))))

            assertFalse(
                changed.name,
                recorder.append(
                    frame(
                        data = changed.pcm,
                        sampleRateHz = changed.sampleRateHz,
                        channelCount = changed.channelCount,
                        bitsPerSample = changed.bitsPerSample,
                    ),
                ),
            )
            assertNull(changed.name, recorder.stop())
            assertFalse(changed.name, output.exists())
            recorder.close()
        }
    }

    @Test
    fun misaligned_pcm_is_rejected_and_cannot_look_like_a_successful_wav() {
        val output = outputFile("misaligned.wav")
        val recorder = WavRecorder()

        recorder.start(output, PCM_16K_MONO)
        assertFalse(recorder.append(frame(byteArrayOf(1, 2, 3))))

        assertNull(recorder.stop())
        assertFalse(output.exists())
        recorder.close()
    }

    @Test
    fun a_full_bounded_queue_rejects_immediately_and_marks_the_recording_failed() {
        val output = outputFile("queue-full.wav")
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val recorder = WavRecorder(queueCapacity = 1) {
            writerEntered.countDown()
            releaseWriter.await(5, TimeUnit.SECONDS)
        }

        try {
            recorder.start(output, PCM_16K_MONO)
            assertTrue(recorder.append(frame(byteArrayOf(1, 2))))
            assertTrue("writer did not start", writerEntered.await(2, TimeUnit.SECONDS))
            assertTrue(recorder.append(frame(byteArrayOf(3, 4))))

            val startedAt = System.nanoTime()
            val accepted = recorder.append(frame(byteArrayOf(5, 6)))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertFalse(accepted)
            assertTrue("append blocked for ${elapsedMs}ms", elapsedMs < 500)
        } finally {
            releaseWriter.countDown()
        }

        assertNull(recorder.stop())
        assertFalse(output.exists())
        recorder.close()
    }

    @Test
    fun close_finalizes_an_empty_recording_and_prevents_reuse() {
        val output = outputFile("empty.wav")
        val recorder = WavRecorder()

        recorder.start(output, PCM_16K_MONO)
        recorder.close()

        val bytes = output.readBytes()
        assertEquals(44, bytes.size)
        assertEquals("RIFF", ascii(bytes, 0, 4))
        assertEquals(36, int32(bytes, 4))
        assertEquals("WAVE", ascii(bytes, 8, 4))
        assertEquals(0, int32(bytes, 40))
        assertFalse(recorder.append(frame(byteArrayOf(1, 2))))
        assertThrows(IllegalStateException::class.java) {
            recorder.start(outputFile("reuse.wav"), PCM_16K_MONO)
        }
    }

    @Test
    fun close_releases_the_single_writer_thread_after_a_successful_recording() {
        val output = outputFile("close-worker.wav")
        val writerThread = AtomicReference<Thread>()
        val writerRan = CountDownLatch(1)
        val recorder = WavRecorder(beforeWrite = {
            writerThread.compareAndSet(null, Thread.currentThread())
            writerRan.countDown()
        })

        recorder.start(output, PCM_16K_MONO)
        assertTrue(recorder.append(frame(byteArrayOf(1, 2))))
        assertTrue("writer did not run", writerRan.await(2, TimeUnit.SECONDS))
        recorder.close()

        val thread = writerThread.get()
        assertNotNull(thread)
        thread.join(1_000)
        assertFalse("writer thread must terminate after close", thread.isAlive)
        assertEquals(46, output.length())
    }

    @Test
    fun close_after_a_writer_error_releases_the_worker_and_removes_the_failed_output() {
        val output = outputFile("close-error.wav")
        val writerThread = AtomicReference<Thread>()
        val writerRan = CountDownLatch(1)
        val recorder = WavRecorder(beforeWrite = {
            writerThread.compareAndSet(null, Thread.currentThread())
            writerRan.countDown()
        })

        recorder.start(output, PCM_16K_MONO)
        assertTrue(recorder.append(frame(byteArrayOf(1, 2))))
        assertTrue("writer did not run", writerRan.await(2, TimeUnit.SECONDS))
        assertFalse(recorder.append(frame(byteArrayOf(1, 2, 3))))
        recorder.close()

        val thread = writerThread.get()
        assertNotNull(thread)
        thread.join(1_000)
        assertFalse("writer thread must terminate after close", thread.isAlive)
        assertFalse(output.exists())
    }

    private fun outputFile(name: String): File = File(temporaryFolder.root, name)

    private fun frame(
        data: ByteArray,
        sampleRateHz: Int = PCM_16K_MONO.sampleRateHz,
        channelCount: Int = PCM_16K_MONO.channelCount,
        bitsPerSample: Int = PCM_16K_MONO.bitsPerSample,
    ): PcmFrame = PcmFrame(
        data = data,
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        bitsPerSample = bitsPerSample,
        timestampNs = 123L,
    )

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private fun int16(bytes: ByteArray, offset: Int): Int =
        littleEndian(bytes).getShort(offset).toInt() and 0xffff

    private fun int32(bytes: ByteArray, offset: Int): Int =
        littleEndian(bytes).getInt(offset)

    private fun littleEndian(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private companion object {
        val PCM_16K_MONO = PcmFormat(
            sampleRateHz = 16_000,
            channelCount = 1,
            bitsPerSample = 16,
        )
    }
}
