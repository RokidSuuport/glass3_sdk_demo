package com.rokid.glass.mediastream.sender.capture

import android.graphics.BitmapFactory
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Nv21SnapshotWriterInstrumentedTest {

    @Test
    fun write_places_a_decodable_4x4_jpeg_in_app_scoped_pictures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 4 x 4 NV21 = 16-byte Y plane + 8-byte interleaved VU plane.
        // Neutral luminance/chroma keeps the fixture valid without depending on scene content.
        val neutralNv21 = ByteArray(24) { 0x80.toByte() }
        var snapshot: File? = null

        try {
            val writtenSnapshot = Nv21SnapshotWriter(context).write(
                data = neutralNv21,
                width = 4,
                height = 4,
            )
            snapshot = writtenSnapshot

            val picturesDirectory = requireNotNull(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            )
            assertEquals(
                picturesDirectory.canonicalFile,
                writtenSnapshot.canonicalFile.parentFile,
            )
            assertTrue(writtenSnapshot.isFile)
            assertTrue(writtenSnapshot.length() > 0L)

            val bitmap = BitmapFactory.decodeFile(writtenSnapshot.absolutePath)
            assertNotNull(bitmap)
            requireNotNull(bitmap).let { decoded ->
                try {
                    assertEquals(4, decoded.width)
                    assertEquals(4, decoded.height)
                } finally {
                    decoded.recycle()
                }
            }
        } finally {
            // Never clean the directory: remove only the unique file returned by this test run.
            snapshot?.delete()
        }
    }
}
