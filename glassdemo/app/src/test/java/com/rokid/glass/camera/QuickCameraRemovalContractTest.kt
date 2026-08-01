package com.rokid.glass.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCameraRemovalContractTest {
    @Test
    fun productionCodeNoLongerUsesQuickCameraManager() {
        val sourceRoot = File("src/main/java")
        val productionSources = sourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

        assertFalse(File(sourceRoot, "com/rokid/glass/camera/QuickCameraManager.kt").exists())
        productionSources.forEach { source ->
            assertFalse(
                "${source.path} still references QuickCameraManager",
                source.readText().contains("QuickCameraManager")
            )
        }

        val receiver = File(sourceRoot, "com/rokid/glass/MessageReceiveActivity.kt").readText()
        val sender = File(sourceRoot, "com/rokid/glass/SendMessageActivity.kt").readText()
        assertTrue(receiver.contains("ImageFileUtils.saveJpeg"))
        assertFalse(sender.contains("takePhotoAndAwait"))
    }
}
