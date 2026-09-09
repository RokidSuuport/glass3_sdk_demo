package com.rokid.glass.mediastream.transport.webrtc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcDependencyBoundaryTest {
    @Test
    fun `transport production sources never open glass sdk camera share media service or android audio record`() {
        val violations = productionSources().flatMap { source ->
            val text = source.readText()
            FORBIDDEN_DEPENDENCIES.mapNotNull { forbidden ->
                if (forbidden.pattern.containsMatchIn(text)) {
                    "${source.relativeTo(sourceRoot()).path}: ${forbidden.description}"
                } else {
                    null
                }
            }
        }

        assertTrue("Forbidden direct media dependencies found:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `transport imports only the livekit prefixed webrtc package`() {
        val violations = productionSources().filter { source ->
            UNPREFIXED_WEBRTC.containsMatchIn(source.readText())
        }

        assertTrue("Unprefixed org.webrtc references found: $violations", violations.isEmpty())
    }

    @Test
    fun `real audio module disables recording and mutes playback only after module creation`() {
        val source = sourceRoot()
            .resolve("com/rokid/glass/mediastream/transport/webrtc/audio/WebRtcAudioDeviceModuleFactory.kt")
            .readText()
        val createIndex = source.indexOf(".createAudioDeviceModule()")
        val disableRecordIndex = source.indexOf(".setAudioRecordEnabled(false)")
        val muteSpeakerIndex = source.indexOf(".setSpeakerMute(true)")

        assertTrue("Factory must create the module before disabling Android recording", createIndex >= 0)
        assertTrue(
            "setAudioRecordEnabled(false) must be called on the created module",
            disableRecordIndex > createIndex,
        )
        assertTrue("setSpeakerMute(true) must follow module creation", muteSpeakerIndex > createIndex)
    }

    private fun productionSources(): List<File> = sourceRoot()
        .walkTopDown()
        .filter { file -> file.isFile && file.extension in setOf("kt", "java") }
        .toList()
        .also { sources -> assertTrue("No transport production sources found", sources.isNotEmpty()) }

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("webrtc-transport/src/main/java"),
            File("android/webrtc-transport/src/main/java"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Cannot locate webrtc-transport production source root from ${File(".").absolutePath}")
    }

    private data class ForbiddenDependency(
        val description: String,
        val pattern: Regex,
    )

    private companion object {
        val FORBIDDEN_DEPENDENCIES = listOf(
            ForbiddenDependency("Rokid security SDK", Regex("com[.]rokid[.]security|\\bGlassSdk\\b")),
            ForbiddenDependency("CameraShare", Regex("\\bCameraShare(?:Helper)?\\b")),
            ForbiddenDependency("IMediaServer", Regex("\\bIMediaServer\\b")),
            ForbiddenDependency(
                "Android AudioRecord",
                Regex("android[.]media[.]AudioRecord|\\bAudioRecord\\s*[(]"),
            ),
        )
        val UNPREFIXED_WEBRTC = Regex("(?<!livekit[.])\\borg[.]webrtc\\b")
    }
}
