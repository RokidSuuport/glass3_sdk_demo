package com.rokid.glass.utils

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFileUtilsTest {
    @Test
    fun imageFileNamePreservesExistingFormat() {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .parse("2026-08-01 09:08:07")!!

        assertEquals("IMG_20260801_090807.jpg", ImageFileUtils.imageFileName(date))
    }
}
