package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Startup activity that creates a marker file under user home directory.
 * The file is named .<pid>.hot-reload and is removed when the IDE exits.
 */
class HotReloadStartupActivity : ProjectActivity {
    companion object {
        private val LOG = Logger.getInstance(HotReloadStartupActivity::class.java)

        @Volatile
        private var markerFileCreated = false
        private var markerFilePath: Path? = null
    }

    override suspend fun execute(project: Project) {
        createMarkerFileIfNeeded()
    }

    @Synchronized
    private fun createMarkerFileIfNeeded() {
        if (markerFileCreated) {
            return
        }

        val pid = ProcessHandle.current().pid()
        val userHome = System.getProperty("user.home")
        val markerFile = Path.of(userHome, ".$pid.hot-reload")

        try {
            Files.createFile(markerFile)
            markerFilePath = markerFile
            markerFileCreated = true
            LOG.info("Created hot-reload marker file: $markerFile")

            // Register cleanup on application disposal
            val application = ApplicationManager.getApplication()
            Disposer.register(application, Disposable {
                deleteMarkerFile()
            })
        } catch (e: Exception) {
            LOG.warn("Failed to create marker file: $markerFile", e)
        }
    }

    private fun deleteMarkerFile() {
        val path = markerFilePath ?: return
        try {
            Files.deleteIfExists(path)
            LOG.info("Deleted hot-reload marker file: $path")
        } catch (e: Exception) {
            LOG.warn("Failed to delete marker file: $path", e)
        }
    }
}
