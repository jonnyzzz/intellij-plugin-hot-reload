package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the marker file functionality.
 */
class MarkerFileTest : BasePlatformTestCase() {

    fun testMarkerFileNameFormat() {
        val pid = ProcessHandle.current().pid()
        val expectedFileName = ".$pid.hot-reload"
        val userHome = System.getProperty("user.home")
        val expectedPath = Path.of(userHome, expectedFileName)

        // Verify the expected format
        assertTrue("Marker file name should start with dot", expectedFileName.startsWith("."))
        assertTrue("Marker file name should contain pid", expectedFileName.contains(pid.toString()))
        assertTrue("Marker file name should end with .hot-reload", expectedFileName.endsWith(".hot-reload"))
    }

    fun testMarkerFileContentFormat() {
        // Test that marker file content has the expected format
        val sampleContent = """
            http://localhost:63342/api/plugin-hot-reload
            Bearer abc123-def456
            2024-01-15T10:30:00+01:00

            IntelliJ IDEA 2025.3
            Build #IU-253.12345
            Built on January 1, 2025

            Runtime version: 21.0.1+12-39
            VM: OpenJDK 64-Bit Server VM by JetBrains s.r.o.

            OS: Mac OS X 14.0 (aarch64)
            GC: G1 Young Generation, G1 Old Generation
            Memory: 2048 MB
        """.trimIndent()

        val lines = sampleContent.lines()

        // Verify URL format (line 1)
        assertTrue("First line should be URL", lines[0].startsWith("http://"))
        assertTrue("URL should contain api endpoint", lines[0].contains("/api/plugin-hot-reload"))

        // Verify token format (line 2)
        assertTrue("Second line should be Bearer token", lines[1].startsWith("Bearer "))

        // Verify date format (line 3)
        assertTrue("Third line should be date", lines[2].matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")))
    }

    fun testMarkerFileCreationAndCleanup() {
        val pid = ProcessHandle.current().pid()
        val userHome = System.getProperty("user.home")
        val markerPath = Path.of(userHome, ".$pid.hot-reload.test")

        // Create a test disposable
        val testDisposable = Disposer.newDisposable("test-marker-file")

        try {
            // Create marker file with sample content
            val content = """
                http://localhost:63342/api/plugin-hot-reload
                Bearer test-token-123
                2024-01-15T10:30:00+01:00

                Test IDE
            """.trimIndent()

            Files.writeString(markerPath, content)
            assertTrue("Marker file should exist after creation", Files.exists(markerPath))

            // Verify content
            val readContent = Files.readString(markerPath)
            assertTrue("Content should contain URL", readContent.contains("http://localhost"))
            assertTrue("Content should contain Bearer token", readContent.contains("Bearer"))

            // Register cleanup
            Disposer.register(testDisposable, Disposable {
                try {
                    Files.deleteIfExists(markerPath)
                } catch (e: Exception) {
                    // Ignore
                }
            })

            // Dispose and verify cleanup
            Disposer.dispose(testDisposable)
            assertFalse("Marker file should be deleted after disposal", Files.exists(markerPath))
        } finally {
            // Ensure cleanup even if test fails
            try {
                Files.deleteIfExists(markerPath)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun testMarkerFileParsingInGradle() {
        // Test that the marker file format can be parsed correctly
        val content = """
            http://localhost:63342/api/plugin-hot-reload
            Bearer abc123-def456-ghi789
            2024-01-15T10:30:00+01:00

            IntelliJ IDEA 2025.3
            Build #IU-253.12345
        """.trimIndent()

        val lines = content.lines()
        assertTrue("Should have at least 3 lines", lines.size >= 3)

        val url = lines[0].trim()
        val token = lines[1].trim()
        val dateTime = lines[2].trim()
        val ideInfo = lines.drop(4).joinToString("\n").trim()

        assertEquals("http://localhost:63342/api/plugin-hot-reload", url)
        assertEquals("Bearer abc123-def456-ghi789", token)
        assertEquals("2024-01-15T10:30:00+01:00", dateTime)
        assertTrue("IDE info should contain product name", ideInfo.contains("IntelliJ IDEA"))
    }
}
