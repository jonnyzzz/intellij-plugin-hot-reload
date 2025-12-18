package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application-level service that manages the hot-reload marker file.
 *
 * Creates .<pid>.hot-reload file in user home on init,
 * removes it when the service is disposed (on IDE exit).
 */
@Service(Service.Level.APP)
class HotReloadMarkerService : Disposable {
    companion object {
        private val LOG = Logger.getInstance(HotReloadMarkerService::class.java)
    }

    private val markerFilePath: Path?

    init {
        val pid = ProcessHandle.current().pid()
        val userHome = System.getProperty("user.home")
        val markerFile = Path.of(userHome, ".$pid.hot-reload")

        markerFilePath = try {
            Files.createFile(markerFile)
            LOG.info("Created hot-reload marker file: $markerFile")
            markerFile
        } catch (e: Exception) {
            LOG.warn("Failed to create marker file: $markerFile", e)
            null
        }
    }

    override fun dispose() {
        val path = markerFilePath ?: return
        try {
            Files.deleteIfExists(path)
            LOG.info("Deleted hot-reload marker file: $path")
        } catch (e: Exception) {
            LOG.warn("Failed to delete marker file: $path", e)
        }
    }
}
