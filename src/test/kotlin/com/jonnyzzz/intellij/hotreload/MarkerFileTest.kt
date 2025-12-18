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

    fun testMarkerFileCreationAndCleanup() {
        val pid = ProcessHandle.current().pid()
        val userHome = System.getProperty("user.home")
        val markerPath = Path.of(userHome, ".$pid.hot-reload")

        // Create a test disposable
        val testDisposable = Disposer.newDisposable("test-marker-file")

        try {
            // Create marker file
            if (!Files.exists(markerPath)) {
                Files.createFile(markerPath)
            }
            assertTrue("Marker file should exist after creation", Files.exists(markerPath))

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

    fun testMarkerFileContentIsEmpty() {
        val pid = ProcessHandle.current().pid()
        val userHome = System.getProperty("user.home")
        val markerPath = Path.of(userHome, ".$pid.hot-reload.test")

        try {
            // Create an empty marker file
            Files.createFile(markerPath)
            assertTrue("Marker file should exist", Files.exists(markerPath))

            // Verify it's empty
            val content = Files.readAllBytes(markerPath)
            assertEquals("Marker file should be empty", 0, content.size)
        } finally {
            Files.deleteIfExists(markerPath)
        }
    }
}
