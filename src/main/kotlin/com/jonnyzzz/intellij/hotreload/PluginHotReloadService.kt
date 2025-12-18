package com.jonnyzzz.intellij.hotreload

import com.intellij.ide.plugins.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
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
    companion object {
        private val LOG = Logger.getInstance(PluginHotReloadService::class.java)
    }

    data class ReloadResult(
        val success: Boolean,
        val message: String,
        val pluginId: String? = null,
        val restartRequired: Boolean = false
    )

    /**
     * Reload a plugin from the provided zip file bytes.
     */
    fun reloadPlugin(zipBytes: ByteArray): ReloadResult {
        LOG.info("Starting plugin hot reload, zip size: ${zipBytes.size} bytes")

        // Step 1: Extract plugin ID from the zip
        val pluginId = try {
            extractPluginId(zipBytes)
        } catch (e: Exception) {
            LOG.error("Failed to extract plugin ID from zip", e)
            return ReloadResult(false, "Failed to extract plugin ID: ${e.message}")
        }

        if (pluginId == null) {
            return ReloadResult(false, "Could not find plugin.xml in the zip file")
        }

        LOG.info("Extracted plugin ID: $pluginId")

        // Step 2: Save zip to temp file
        val tempZipFile = try {
            val tempFile = Files.createTempFile("plugin-hot-reload-", ".zip")
            Files.write(tempFile, zipBytes)
            tempFile
        } catch (e: Exception) {
            LOG.error("Failed to save zip to temp file", e)
            return ReloadResult(false, "Failed to save zip: ${e.message}", pluginId)
        }

        try {
            return reloadPluginFromFile(pluginId, tempZipFile)
        } finally {
            try {
                Files.deleteIfExists(tempZipFile)
            } catch (e: Exception) {
                LOG.warn("Failed to delete temp file: $tempZipFile", e)
            }
        }
    }

    /**
     * Reload a plugin from a zip file on disk.
     */
    fun reloadPluginFromFile(pluginIdString: String, zipFile: Path): ReloadResult {
        val pluginId = PluginId.getId(pluginIdString)

        // Step 3: Find existing plugin
        val existingPlugin = PluginManagerCore.getPlugin(pluginId)
        val existingPluginPath = existingPlugin?.pluginPath

        LOG.info("Existing plugin: ${existingPlugin?.name ?: "not found"}, path: $existingPluginPath")

        // Step 4: Check if dynamic reload is possible
        // Using internal API: DynamicPlugins - no public alternative exists
        if (existingPlugin != null) {
            val descriptor = existingPlugin as? IdeaPluginDescriptorImpl
            @Suppress("UnstableApiUsage")
            if (descriptor != null && !DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor)) {
                LOG.warn("Plugin $pluginId does not support dynamic reload")
                // Still try to do it, but note that restart may be required
            }
        }

        // Step 5: Unload existing plugin if it exists and is enabled
        // Using internal API: PluginInstaller.unloadDynamicPlugin - no public alternative exists
        if (existingPlugin != null && !PluginManagerCore.isDisabled(pluginId)) {
            val descriptor = existingPlugin as? IdeaPluginDescriptorImpl
            if (descriptor != null) {
                LOG.info("Unloading existing plugin: ${existingPlugin.name}")
                @Suppress("UnstableApiUsage")
                val unloaded = PluginInstaller.unloadDynamicPlugin(null, descriptor, true)
                if (!unloaded) {
                    LOG.warn("Failed to unload plugin dynamically, restart may be required")
                }
            }
        }

        // Step 6: Delete old plugin folder (rename first for safety)
        if (existingPluginPath != null && Files.exists(existingPluginPath)) {
            LOG.info("Removing old plugin at: $existingPluginPath")
            try {
                val backupPath = existingPluginPath.resolveSibling("${existingPluginPath.fileName}.old.${System.currentTimeMillis()}")
                Files.move(existingPluginPath, backupPath)
                // Try to delete the backup
                try {
                    NioFiles.deleteRecursively(backupPath)
                } catch (e: Exception) {
                    LOG.warn("Could not delete old plugin folder immediately: $backupPath", e)
                    // Will be cleaned up on restart
                }
            } catch (e: Exception) {
                LOG.error("Failed to remove old plugin folder", e)
                return ReloadResult(false, "Failed to remove old plugin: ${e.message}", pluginIdString)
            }
        }

        // Step 7: Load descriptor from the zip file
        // Using internal API: loadDescriptorFromArtifact - no public alternative exists
        @Suppress("UnstableApiUsage")
        val newDescriptor = try {
            loadDescriptorFromArtifact(zipFile, null)
        } catch (e: Exception) {
            LOG.error("Failed to load descriptor from zip", e)
            return ReloadResult(false, "Failed to load plugin descriptor: ${e.message}", pluginIdString)
        }

        if (newDescriptor == null) {
            return ReloadResult(false, "Failed to load plugin descriptor from zip file", pluginIdString)
        }

        val pluginName = newDescriptor.name
        val pluginVersion = newDescriptor.version

        LOG.info("Installing and loading plugin: $pluginName ($pluginVersion)")

        // Step 8: Install and load the plugin dynamically
        // Using internal API: PluginInstaller.installAndLoadDynamicPlugin - no public alternative exists
        @Suppress("UnstableApiUsage")
        val loaded = try {
            PluginInstaller.installAndLoadDynamicPlugin(zipFile, null, newDescriptor as IdeaPluginDescriptorImpl)
        } catch (e: Exception) {
            LOG.error("Failed to install and load plugin", e)
            return ReloadResult(false, "Failed to load plugin: ${e.message}", pluginIdString, restartRequired = true)
        }

        return if (loaded) {
            LOG.info("Plugin hot reload successful: $pluginName")
            ReloadResult(true, "Plugin $pluginName reloaded successfully", pluginIdString)
        } else {
            LOG.warn("Dynamic plugin load failed, restart required")
            ReloadResult(false, "Plugin installed but restart required", pluginIdString, restartRequired = true)
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
            LOG.error("Failed to parse plugin.xml", e)
            null
        }
    }
}
