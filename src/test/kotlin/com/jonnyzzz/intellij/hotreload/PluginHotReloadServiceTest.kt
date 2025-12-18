package com.jonnyzzz.intellij.hotreload

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for the PluginHotReloadService.
 */
class PluginHotReloadServiceTest : BasePlatformTestCase() {

    fun testExtractPluginIdFromValidZip() {
        val pluginXml = """
            <idea-plugin>
                <id>com.example.test-plugin</id>
                <name>Test Plugin</name>
                <vendor>Test</vendor>
                <depends>com.intellij.modules.platform</depends>
            </idea-plugin>
        """.trimIndent()

        val zipBytes = createPluginZip("test-plugin", pluginXml)

        val service = PluginHotReloadService()
        val pluginId = service.extractPluginId(zipBytes)

        assertEquals("com.example.test-plugin", pluginId)
    }

    fun testExtractPluginIdFromZipWithoutPluginXml() {
        // Create a zip without plugin.xml
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("test-plugin/lib/test.jar"))
                zos.write("dummy content".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val service = PluginHotReloadService()
        val pluginId = service.extractPluginId(zipBytes)

        assertNull("Should return null when plugin.xml is missing", pluginId)
    }

    fun testExtractPluginIdFromNestedPluginXml() {
        val pluginXml = """
            <idea-plugin>
                <id>com.example.nested-plugin</id>
                <name>Nested Plugin</name>
            </idea-plugin>
        """.trimIndent()

        // Create zip with nested plugin.xml
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("nested-plugin/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("nested-plugin/META-INF/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("nested-plugin/META-INF/plugin.xml"))
                zos.write(pluginXml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val service = PluginHotReloadService()
        val pluginId = service.extractPluginId(zipBytes)

        assertEquals("com.example.nested-plugin", pluginId)
    }

    fun testParsePluginIdFromXml() {
        val pluginXml = """
            <idea-plugin>
                <id>com.example.parsed-plugin</id>
                <name>Parsed Plugin</name>
            </idea-plugin>
        """.trimIndent()

        val service = PluginHotReloadService()
        val pluginId = service.parsePluginIdFromXml(pluginXml.toByteArray(StandardCharsets.UTF_8))

        assertEquals("com.example.parsed-plugin", pluginId)
    }

    fun testParsePluginIdFromXmlWithWhitespace() {
        val pluginXml = """
            <idea-plugin>
                <id>
                    com.example.whitespace-plugin
                </id>
                <name>Whitespace Plugin</name>
            </idea-plugin>
        """.trimIndent()

        val service = PluginHotReloadService()
        val pluginId = service.parsePluginIdFromXml(pluginXml.toByteArray(StandardCharsets.UTF_8))

        assertEquals("com.example.whitespace-plugin", pluginId)
    }

    fun testParsePluginIdFromXmlWithoutId() {
        val pluginXml = """
            <idea-plugin>
                <name>No ID Plugin</name>
            </idea-plugin>
        """.trimIndent()

        val service = PluginHotReloadService()
        val pluginId = service.parsePluginIdFromXml(pluginXml.toByteArray(StandardCharsets.UTF_8))

        assertNull("Should return null when id element is missing", pluginId)
    }

    fun testReloadPluginWithEmptyBytes() {
        val service = PluginHotReloadService()
        val result = service.reloadPlugin(ByteArray(0))

        assertFalse("Should fail with empty bytes", result.success)
        assertTrue("Error message should mention plugin", result.message.contains("plugin", ignoreCase = true))
    }

    fun testReloadPluginWithInvalidZip() {
        val service = PluginHotReloadService()
        val result = service.reloadPlugin("not a zip file".toByteArray())

        assertFalse("Should fail with invalid zip", result.success)
    }

    fun testReloadPluginWithValidZipButNoPluginXml() {
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("test/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("test/file.txt"))
                zos.write("content".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val service = PluginHotReloadService()
        val result = service.reloadPlugin(zipBytes)

        assertFalse("Should fail when plugin.xml is missing", result.success)
        assertTrue("Should mention plugin.xml in error", result.message.contains("plugin", ignoreCase = true))
    }

    private fun createPluginZip(pluginName: String, pluginXml: String): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                // Add directory entries
                zos.putNextEntry(ZipEntry("$pluginName/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("$pluginName/META-INF/"))
                zos.closeEntry()

                // Add plugin.xml
                zos.putNextEntry(ZipEntry("$pluginName/META-INF/plugin.xml"))
                zos.write(pluginXml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()

                // Add a dummy lib directory
                zos.putNextEntry(ZipEntry("$pluginName/lib/"))
                zos.closeEntry()
            }
            baos.toByteArray()
        }
    }
}
