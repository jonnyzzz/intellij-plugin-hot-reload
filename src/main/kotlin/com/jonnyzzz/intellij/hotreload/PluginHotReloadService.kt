package com.jonnyzzz.intellij.hotreload

import com.intellij.ide.plugins.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.io.NioFiles
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service responsible for hot-reloading plugins.
 *
 * The reload process:
 * 1. Parse plugin.xml from the uploaded zip to get plugin ID
 * 2. Find and unload the existing plugin with that ID
 * 3. Remove the old plugin folder
 * 4. Extract the new plugin to the plugins folder
 * 5. Load the new plugin dynamically
 *
 * Note: This service uses IntelliJ Platform internal APIs for plugin management
 * (DynamicPlugins, PluginInstaller, loadDescriptorFromArtifact) as there are no
 * public alternatives for dynamic plugin loading. These APIs may change without notice.
 */
@Service(Service.Level.APP)
class PluginHotReloadService {
    private val log = thisLogger()

    /**
     * Progress reporter interface for streaming reload status.
     */
    interface ProgressReporter {
        fun report(message: String)
        fun reportError(message: String)
    }

    /**
     * No-op progress reporter for cases where progress isn't needed.
     */
    object NoOpProgressReporter : ProgressReporter {
        override fun report(message: String) {}
        override fun reportError(message: String) {}
    }

    data class ReloadResult(
        val success: Boolean,
        val message: String,
        val pluginId: String? = null,
        val pluginName: String? = null,
        val restartRequired: Boolean = false
    )

    /**
     * Reload a plugin from the provided zip file bytes.
     */
    fun reloadPlugin(zipBytes: ByteArray, progress: ProgressReporter = NoOpProgressReporter): ReloadResult {
        val msg = "Starting plugin hot reload, zip size: ${zipBytes.size} bytes"
        log.info(msg)
        progress.report(msg)

        // Step 1: Extract plugin ID from the zip
        progress.report("Extracting plugin ID from zip...")
        val pluginId = try {
            extractPluginId(zipBytes)
        } catch (e: Exception) {
            val errorMsg = "Failed to extract plugin ID: ${e.message}"
            log.error("Failed to extract plugin ID from zip", e)
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg)
        }

        if (pluginId == null) {
            val errorMsg = "Could not find plugin.xml in the zip file"
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg)
        }

        progress.report("Plugin ID: $pluginId")
        log.info("Extracted plugin ID: $pluginId")

        // Step 2: Save zip to temp file
        progress.report("Saving zip to temp file...")
        val tempZipFile = try {
            val tempFile = Files.createTempFile("plugin-hot-reload-", ".zip")
            Files.write(tempFile, zipBytes)
            tempFile
        } catch (e: Exception) {
            val errorMsg = "Failed to save zip: ${e.message}"
            log.error("Failed to save zip to temp file", e)
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginId)
        }

        try {
            return reloadPluginFromFile(pluginId, tempZipFile, progress)
        } finally {
            try {
                Files.deleteIfExists(tempZipFile)
            } catch (e: Exception) {
                log.warn("Failed to delete temp file: $tempZipFile", e)
            }
        }
    }

    /**
     * Reload a plugin from a zip file on disk.
     */
    fun reloadPluginFromFile(
        pluginIdString: String,
        zipFile: Path,
        progress: ProgressReporter = NoOpProgressReporter
    ): ReloadResult {
        val pluginId = PluginId.getId(pluginIdString)

        // Step 3: Find existing plugin
        progress.report("Looking for existing plugin...")
        val existingPlugin = PluginManagerCore.getPlugin(pluginId)
        val existingPluginPath = existingPlugin?.pluginPath

        val existingMsg = "Existing plugin: ${existingPlugin?.name ?: "not found"}, path: $existingPluginPath"
        log.info(existingMsg)
        progress.report(existingMsg)

        // Step 4: Check if dynamic reload is possible
        // Using internal API: DynamicPlugins - no public alternative exists
        if (existingPlugin != null) {
            val descriptor = existingPlugin as? IdeaPluginDescriptorImpl
            @Suppress("UnstableApiUsage")
            if (descriptor != null && !DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor)) {
                val warnMsg = "Plugin $pluginId does not support dynamic reload, will try anyway"
                log.warn(warnMsg)
                progress.report(warnMsg)
            }
        }

        // Step 5: Unload existing plugin if it exists and is enabled
        // Using internal API: PluginInstaller.unloadDynamicPlugin - no public alternative exists
        if (existingPlugin != null && !PluginManagerCore.isDisabled(pluginId)) {
            val descriptor = existingPlugin as? IdeaPluginDescriptorImpl
            if (descriptor !== null) {
                val unloadMsg = "Unloading existing plugin: ${existingPlugin.name}"
                log.info(unloadMsg)
                progress.report(unloadMsg)

                @Suppress("UnstableApiUsage")
                val unloaded = PluginInstaller.unloadDynamicPlugin(null, descriptor, true)
                if (!unloaded) {
                    val warnMsg = "Failed to unload plugin dynamically, restart may be required"
                    log.warn(warnMsg)
                    progress.report(warnMsg)
                } else {
                    progress.report("Plugin unloaded successfully")
                }
            }
        }

        // Step 6: Delete old plugin folder (rename first for safety)
        if (existingPluginPath != null && Files.exists(existingPluginPath)) {
            progress.report("Removing old plugin at: $existingPluginPath")
            log.info("Removing old plugin at: $existingPluginPath")
            try {
                val backupPath = existingPluginPath.resolveSibling("${existingPluginPath.fileName}.old.${System.currentTimeMillis()}")
                Files.move(existingPluginPath, backupPath)
                // Try to delete the backup
                try {
                    NioFiles.deleteRecursively(backupPath)
                    progress.report("Old plugin folder removed")
                } catch (e: Exception) {
                    log.warn("Could not delete old plugin folder immediately: $backupPath", e)
                    progress.report("Old plugin folder will be cleaned up on restart")
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to remove old plugin: ${e.message}"
                log.error("Failed to remove old plugin folder", e)
                progress.reportError(errorMsg)
                return ReloadResult(false, errorMsg, pluginIdString)
            }
        }

        // Step 7: Load descriptor from the zip file
        // Using internal API: loadDescriptorFromArtifact - no public alternative exists
        progress.report("Loading plugin descriptor from zip...")
        @Suppress("UnstableApiUsage")
        val newDescriptor = try {
            loadDescriptorFromArtifact(zipFile, null)
        } catch (e: Exception) {
            val errorMsg = "Failed to load plugin descriptor: ${e.message}"
            log.error("Failed to load descriptor from zip", e)
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginIdString)
        }

        if (newDescriptor === null) {
            val errorMsg = "Failed to load plugin descriptor from zip file"
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginIdString)
        }

        val pluginName = newDescriptor.name
        val pluginVersion = newDescriptor.version

        val installMsg = "Installing and loading plugin: $pluginName ($pluginVersion)"
        log.info(installMsg)
        progress.report(installMsg)

        // Step 8: Install and load the plugin dynamically
        // Using internal API: PluginInstaller.installAndLoadDynamicPlugin - no public alternative exists
        @Suppress("UnstableApiUsage")
        val loaded = try {
            PluginInstaller.installAndLoadDynamicPlugin(zipFile, null, newDescriptor as IdeaPluginDescriptorImpl)
        } catch (e: Exception) {
            val errorMsg = "Failed to load plugin: ${e.message}"
            log.error("Failed to install and load plugin", e)
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginIdString, pluginName, restartRequired = true)
        }

        return if (loaded) {
            val successMsg = "Plugin $pluginName reloaded successfully"
            log.info("Plugin hot reload successful: $pluginName")
            progress.report(successMsg)
            ReloadResult(true, successMsg, pluginIdString, pluginName)
        } else {
            val failMsg = "Plugin installed but restart required"
            log.warn("Dynamic plugin load failed, restart required")
            progress.reportError(failMsg)
            ReloadResult(false, failMsg, pluginIdString, pluginName, restartRequired = true)
        }
    }

    /**
     * Extract the plugin ID from plugin.xml inside the zip file.
     */
    internal fun extractPluginId(zipBytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Look for plugin.xml in the standard locations
                val name = entry.name
                if (name.endsWith("/META-INF/plugin.xml") || name == "META-INF/plugin.xml") {
                    val xmlBytes = zis.readBytes()
                    return parsePluginIdFromXml(xmlBytes)
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * Parse the plugin ID from plugin.xml content.
     */
    internal fun parsePluginIdFromXml(xmlBytes: ByteArray): String? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xmlBytes))

            val idNodes = doc.getElementsByTagName("id")
            if (idNodes.length > 0) {
                idNodes.item(0).textContent?.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("Failed to parse plugin.xml", e)
            null
        }
    }
}
