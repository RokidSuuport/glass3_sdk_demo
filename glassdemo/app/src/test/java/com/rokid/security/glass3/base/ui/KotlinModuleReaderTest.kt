package com.rokid.security.glass3.base.ui

import org.junit.Test
import java.io.File

class KotlinModuleReaderTest {

    @Test
    fun readKotlinModule() {
        val moduleFile = File("/Users/rokid/Desktop/qrcode_release.kotlin_module")

        println("=== Kotlin Module Reader ===")
        println("Looking for: ${moduleFile.absolutePath}")
        println("Current working directory: ${File(".").absolutePath}")
        println()

        if (!moduleFile.exists()) {
            println("❌ File not found!")
            println("\n💡 Trying to find kotlin_module files in build directory...")

            val foundFiles = File("build").walk()
                .filter { it.name.endsWith(".kotlin_module") }
                .toList()

            if (foundFiles.isNotEmpty()) {
                println("\n✅ Found ${foundFiles.size} kotlin_module file(s):")
                foundFiles.forEach { file ->
                    println("   • ${file.absolutePath}")
                }

                val firstFile = foundFiles.first()
                println("\n📖 Parsing first file: ${firstFile.name}")
                parseModuleContent(firstFile)
            } else {
                println("❌ No kotlin_module files found in build directory")
                println("\n💡 Tip: Build the project first:")
                println("   ./gradlew :glass3:qrcode:assembleRelease")
            }
            return
        }

        println("✅ File found!")
        parseModuleContent(moduleFile)
    }

    private fun parseModuleContent(file: File) {
        val bytes = file.readBytes()
        println("\n📄 File: ${file.name}")
        println("📏 Size: ${bytes.size} bytes")
        println()

        if (bytes.isEmpty()) {
            println("⚠️ File is empty!")
            return
        }

        println("=== Hex Header (first 64 bytes) ===")
        bytes.take(64).chunked(16).forEachIndexed { index, chunk ->
            val hex = chunk.joinToString(" ") { "%02X".format(it) }
            val ascii = chunk.joinToString("") {
                if (it.toInt() in 32..126) it.toChar().toString() else "."
            }
            println("  ${String.format("%04d", index * 16)}: $hex  $ascii")
        }
        println()

        val content = String(bytes, Charsets.ISO_8859_1)

        println("=== All Readable Strings ===")
        val strings = extractReadableStrings(content)
        strings.forEach { str ->
            println("  • $str")
        }
        println()

        println("=== Kotlin Version Detection ===")
        detectKotlinVersion(bytes, content)
        println()

        println("=== Package Names ===")
        val packagePattern = Regex("""com/[^/\s]+(?:/[^/\s]+)*""")
        val packages = packagePattern.findAll(content)
            .map { it.value.replace('/', '.') }
            .distinct()
            .sorted()

        if (packages.toList().isNotEmpty()) {
            packages.forEach { pkg ->
                println("  📦 $pkg")
            }
        } else {
            println("  No package names found")
        }
    }


    private fun extractReadableStrings(content: String): List<String> {
        return content.split(Regex("[^\\x20-\\x7E]+"))
            .filter { it.length > 2 && it.any { c -> c.isLetterOrDigit() } }
            .distinct()
            .sortedBy { it.length }
    }

    private fun detectKotlinVersion(bytes: ByteArray, content: String) {
        // 方法 1: 从文件头部解析魔术字节和版本
        if (bytes.size >= 4) {
            val magicBytes = bytes.take(4)
            println("Magic bytes: ${magicBytes.joinToString(" ") { "%02X".format(it) }}")

            if (bytes.size >= 8) {
                val versionByte = bytes[3].toInt() and 0xFF
                println("Version indicator (byte 4): $versionByte")

                // Kotlin metadata 版本映射
                val metadataVersions = mapOf(
                    1 to "1.0.x",
                    2 to "1.1.x",
                    3 to "1.2.x - 1.3.x",
                    4 to "1.4.x",
                    5 to "1.5.x",
                    6 to "1.6.x",
                    7 to "1.7.x",
                    8 to "1.8.x",
                    9 to "1.9.x",
                    10 to "2.0.x"
                )
                metadataVersions[versionByte]?.let {
                    println("Possible Kotlin metadata version: $it")
                }
            }
        }
        println()

        // 方法 2: 查找所有可能的版本号模式
        val versionPatterns = listOf(
            Regex("""(\d+\.\d+\.\d+)"""),
            Regex("""(\d+\.\d+)"""),
            Regex("""kotlin[_-]?(\d+\.\d+\.\d+)"""),
            Regex("""KOTLIN[_-]?(\d+\.\d+\.\d+)"""),
            Regex("""([12]\.\d+\.\d+)""")
        )

        var found = false
        versionPatterns.forEachIndexed { index, pattern ->
            val matches = pattern.findAll(content).map { it.value }.toSet()
            if (matches.isNotEmpty()) {
                println("Pattern ${index + 1} found: ${matches.joinToString(", ")}")
                found = true
            }
        }

        if (!found) {
            println("No version patterns found in readable strings")
        }
        println()

        // 方法 3: 查看完整的二进制内容中的版本信息
        println("Searching in raw bytes for version-like patterns...")
        val hexString = bytes.joinToString("") { "%02X".format(it) }

        // 扩展的 Kotlin 版本列表 (ASCII 十六进制)
        val commonVersions = mapOf(
            // Kotlin 1.0.x - 1.3.x
            "1.0.0" to "31 2E 30 2E 30",
            "1.0.7" to "31 2E 30 2E 37",
            "1.1.0" to "31 2E 31 2E 30",
            "1.1.61" to "31 2E 31 2E 36 31",
            "1.2.0" to "31 2E 32 2E 30",
            "1.2.71" to "31 2E 32 2E 37 31",
            "1.3.0" to "31 2E 33 2E 30",
            "1.3.72" to "31 2E 33 2E 37 32",

            // Kotlin 1.4.x
            "1.4.0" to "31 2E 34 2E 30",
            "1.4.32" to "31 2E 34 2E 33 32",

            // Kotlin 1.5.x
            "1.5.0" to "31 2E 35 2E 30",
            "1.5.32" to "31 2E 35 2E 33 32",

            // Kotlin 1.6.x
            "1.6.0" to "31 2E 36 2E 30",
            "1.6.21" to "31 2E 36 2E 32 31",

            // Kotlin 1.7.x
            "1.7.0" to "31 2E 37 2E 30",
            "1.7.22" to "31 2E 37 2E 32 32",

            // Kotlin 1.8.x
            "1.8.0" to "31 2E 38 2E 30",
            "1.8.22" to "31 2E 38 2E 32 32",

            // Kotlin 1.9.x (常用版本)
            "1.9.0" to "31 2E 39 2E 30",
            "1.9.10" to "31 2E 39 2E 31 30",
            "1.9.20" to "31 2E 39 2E 32 30",
            "1.9.21" to "31 2E 39 2E 32 31",
            "1.9.22" to "31 2E 39 2E 32 32",
            "1.9.23" to "31 2E 39 2E 32 33",
            "1.9.24" to "31 2E 39 2E 32 34",
            "1.9.25" to "31 2E 39 2E 32 35",

            // Kotlin 2.0.x
            "2.0.0" to "32 2E 30 2E 30",
            "2.0.10" to "32 2E 30 2E 31 30",
            "2.0.20" to "32 2E 30 2E 32 30",
            "2.0.21" to "32 2E 30 2E 32 31",

            // Kotlin 2.1.x
            "2.1.0" to "32 2E 31 2E 30",
            "2.1.10" to "32 2E 31 2E 31 30",
            "2.1.20" to "32 2E 31 2E 32 30",

            // Kotlin 2.2.x
            "2.2.0" to "32 2E 32 2E 30",
            "2.2.10" to "32 2E 32 2E 31 30"
        )

        var versionFound = false
        commonVersions.forEach { (version, hex) ->
            if (hexString.contains(hex.replace(" ", ""))) {
                println("  ✓ Found Kotlin $version in binary data")
                println("    Hex pattern: $hex")
                versionFound = true
            }
        }

        if (!versionFound) {
            println("  No known Kotlin versions found in binary data")
            println("\n  💡 Tip: Check your build.gradle files:")
            println("     - Root build.gradle defines the Kotlin compiler version")
            println("     - This module uses kotlin-metadata-jvm for reading metadata")
        }

        // 方法 4: 输出所有数字序列供人工分析
        println("\n=== All numeric sequences in file ===")
        val numericPattern = Regex("""\d+\.\d+[\.\d]*""")
        val allNumbers = numericPattern.findAll(content)
            .map { it.value }
            .distinct()
            .sorted()

        if (allNumbers.toList().isNotEmpty()) {
            allNumbers.forEach { num ->
                println("  $num")
            }
        } else {
            println("  No numeric sequences found")
        }
    }


}
