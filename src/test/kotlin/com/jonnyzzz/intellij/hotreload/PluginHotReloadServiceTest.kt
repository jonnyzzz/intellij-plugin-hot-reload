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

    /**
     * Tests extraction of plugin ID from IntelliJ's actual plugin zip structure:
     * plugin-name/lib/plugin-name.jar containing META-INF/plugin.xml
     *
     * This is the format produced by `./gradlew buildPlugin`.
     */
    fun testExtractPluginIdFromJarInsideZip() {
        val pluginXml = """
            <idea-plugin>
                <id>com.example.jar-nested-plugin</id>
                <name>Jar Nested Plugin</name>
            </idea-plugin>
        """.trimIndent()

        // Create the inner JAR with META-INF/plugin.xml
        val innerJarBytes = ByteArrayOutputStream().use { jarBaos ->
            ZipOutputStream(jarBaos).use { jarZos ->
                jarZos.putNextEntry(ZipEntry("META-INF/"))
                jarZos.closeEntry()
                jarZos.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                jarZos.write(pluginXml.toByteArray(StandardCharsets.UTF_8))
                jarZos.closeEntry()
            }
            jarBaos.toByteArray()
        }

        // Create the outer ZIP with the plugin structure
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("jar-nested-plugin/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("jar-nested-plugin/lib/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("jar-nested-plugin/lib/jar-nested-plugin.jar"))
                zos.write(innerJarBytes)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val service = PluginHotReloadService()
        val pluginId = service.extractPluginId(zipBytes)

        assertEquals("com.example.jar-nested-plugin", pluginId)
    }

    /**
     * Tests that we can extract plugin ID when there are multiple jars,
     * and the plugin.xml is in the second jar (not the first).
     */
    fun testExtractPluginIdFromSecondJarInZip() {
        val pluginXml = """
            <idea-plugin>
                <id>com.example.multi-jar-plugin</id>
                <name>Multi Jar Plugin</name>
            </idea-plugin>
        """.trimIndent()

        // Create a jar WITHOUT plugin.xml (like a dependency)
        val depJarBytes = ByteArrayOutputStream().use { jarBaos ->
            ZipOutputStream(jarBaos).use { jarZos ->
                jarZos.putNextEntry(ZipEntry("com/example/Dep.class"))
                jarZos.write("fake class bytes".toByteArray())
                jarZos.closeEntry()
            }
            jarBaos.toByteArray()
        }

        // Create the plugin jar WITH plugin.xml
        val pluginJarBytes = ByteArrayOutputStream().use { jarBaos ->
            ZipOutputStream(jarBaos).use { jarZos ->
                jarZos.putNextEntry(ZipEntry("META-INF/"))
                jarZos.closeEntry()
                jarZos.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                jarZos.write(pluginXml.toByteArray(StandardCharsets.UTF_8))
                jarZos.closeEntry()
            }
            jarBaos.toByteArray()
        }

        // Create the outer ZIP with multiple jars
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("multi-jar-plugin/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("multi-jar-plugin/lib/"))
                zos.closeEntry()
                // Add dependency jar first (no plugin.xml)
                zos.putNextEntry(ZipEntry("multi-jar-plugin/lib/dependency.jar"))
                zos.write(depJarBytes)
                zos.closeEntry()
                // Add plugin jar second (has plugin.xml)
                zos.putNextEntry(ZipEntry("multi-jar-plugin/lib/plugin.jar"))
                zos.write(pluginJarBytes)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val service = PluginHotReloadService()
        val pluginId = service.extractPluginId(zipBytes)

        assertEquals("com.example.multi-jar-plugin", pluginId)
    }

    /**
     * Tests extraction with the actual mcp-steroids plugin zip structure simulation.
     * This mimics: intellij-mcp-steroid/lib/intellij-mcp-steroid-VERSION.jar
     */
    fun testExtractPluginIdLikeMcpSteroids() {
        val pluginXml = """
            <idea-plugin>
                <id>com.jonnyzzz.intellij.mcp-steroid</id>
                <name>IntelliJ MCP Steroid</name>
                <vendor>jonnyzzz.com</vendor>
            </idea-plugin>
        """.trimIndent()

        // Create the plugin jar
        val pluginJarBytes = ByteArrayOutputStream().use { jarBaos ->
            ZipOutputStream(jarBaos).use { jarZos ->
                jarZos.putNextEntry(ZipEntry("META-INF/"))
                jarZos.closeEntry()
                jarZos.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                jarZos.write(pluginXml.toByteArray(StandardCharsets.UTF_8))
                jarZos.closeEntry()
                // Add some fake class files like a real plugin would have
                jarZos.putNextEntry(ZipEntry("com/jonnyzzz/intellij/mcp/SteroidsMcpServer.class"))
                jarZos.write("fake class".toByteArray())
                jarZos.closeEntry()
            }
            jarBaos.toByteArray()
        }

        // Create the outer ZIP with the exact structure buildPlugin produces
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("intellij-mcp-steroid/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("intellij-mcp-steroid/lib/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("intellij-mcp-steroid/lib/intellij-mcp-steroid-0.84.0-SNAPSHOT.jar"))
                zos.write(pluginJarBytes)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val service = PluginHotReloadService()
        val pluginId = service.extractPluginId(zipBytes)

        assertEquals("com.jonnyzzz.intellij.mcp-steroid", pluginId)
    }

    /**
     * Tests that attempting to reload the hot-reload plugin itself is blocked.
     * The plugin cannot reload itself - it would unload mid-execution.
     */
    fun testSelfReloadIsBlocked() {
        val pluginXml = """
            <idea-plugin>
                <id>com.jonnyzzz.intellij.hot-reload</id>
                <name>Hot Reload</name>
            </idea-plugin>
        """.trimIndent()

        val zipBytes = createPluginZip("hot-reload", pluginXml)

        val service = PluginHotReloadService()
        val result = service.reloadPlugin(zipBytes)

        assertFalse("Should fail when trying to reload self", result.success)
        assertTrue("Should mention cannot reload itself", result.message.contains("cannot", ignoreCase = true) || result.message.contains("Cannot", ignoreCase = true))
        assertEquals("Plugin ID should be set", getSelfPluginId(), result.pluginId)
    }

    /**
     * Tests that attempting to reload via jar structure is also blocked.
     */
    fun testSelfReloadFromJarIsBlocked() {
        val pluginXml = """
            <idea-plugin>
                <id>com.jonnyzzz.intellij.hot-reload</id>
                <name>Hot Reload Plugin</name>
            </idea-plugin>
        """.trimIndent()

        // Create the plugin jar with META-INF/plugin.xml
        val pluginJarBytes = ByteArrayOutputStream().use { jarBaos ->
            ZipOutputStream(jarBaos).use { jarZos ->
                jarZos.putNextEntry(ZipEntry("META-INF/"))
                jarZos.closeEntry()
                jarZos.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                jarZos.write(pluginXml.toByteArray(StandardCharsets.UTF_8))
                jarZos.closeEntry()
            }
            jarBaos.toByteArray()
        }

        // Create the outer ZIP with nested jar structure
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("intellij-plugin-hot-reload/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("intellij-plugin-hot-reload/lib/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("intellij-plugin-hot-reload/lib/intellij-plugin-hot-reload-1.0.0.jar"))
                zos.write(pluginJarBytes)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val service = PluginHotReloadService()
        val result = service.reloadPlugin(zipBytes)

        assertFalse("Should fail when trying to reload self from jar structure", result.success)
        assertEquals("Plugin ID should be set", getSelfPluginId(), result.pluginId)
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
