package com.jonnyzzz.intellij.hotreload

import com.intellij.ide.plugins.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.MemoryDumpHelper
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service responsible for hot-reloading plugins.
 *
 * The reload process:
 * 1. Parse plugin.xml from the uploaded zip to get plugin ID
 * 2. Check if plugin can be safely unloaded
 * 3. Find and unload the existing plugin with that ID
 * 4. Remove the old plugin folder
 * 5. Extract the new plugin to the plugins folder
 * 6. Load the new plugin dynamically
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
        val restartRequired: Boolean = false,
        val memoryDumpPath: String? = null,
        val unloadBlockedReason: String? = null
    )

    /**
     * Reload a plugin from the provided zip file bytes.
     */
    fun reloadPlugin(zipBytes: ByteArray, progress: ProgressReporter = NoOpProgressReporter): ReloadResult {
        progress.report(HotReloadBundle.message("progress.starting", zipBytes.size))

        // Step 1: Extract plugin ID from the zip
        progress.report(HotReloadBundle.message("progress.extracting.id"))
        val pluginId = try {
            extractPluginId(zipBytes)
        } catch (e: Exception) {
            val errorMsg = "Failed to extract plugin ID: ${e.message}"
            log.warn("Failed to extract plugin ID from zip", e)
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg)
        }

        if (pluginId == null) {
            val errorMsg = HotReloadBundle.message("error.no.plugin.id")
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg)
        }

        progress.report(HotReloadBundle.message("progress.plugin.id", pluginId))
        log.info("Extracted plugin ID: $pluginId")

        // Step 2: Check for self-reload attempt
        val selfPluginId = getSelfPluginId()
        if (pluginId == selfPluginId) {
            val errorMsg = HotReloadBundle.message("error.self.reload")
            log.warn("Attempted to reload the hot-reload plugin itself (ID: $selfPluginId)")
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginId)
        }

        // Step 3: Save zip to temp file
        val tempZipFile = try {
            val tempFile = Files.createTempFile("plugin-hot-reload-", ".zip")
            Files.write(tempFile, zipBytes)
            tempFile
        } catch (e: Exception) {
            val errorMsg = "Failed to save zip: ${e.message}"
            log.warn("Failed to save zip to temp file", e)
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

        // Step 4: Find existing plugin
        progress.report(HotReloadBundle.message("progress.looking.for.existing"))
        val existingPlugin = PluginManagerCore.getPlugin(pluginId)
        val existingPluginPath = existingPlugin?.pluginPath

        if (existingPlugin != null) {
            progress.report(HotReloadBundle.message("progress.existing.plugin", existingPlugin.name, existingPluginPath.toString()))
        }

        // Step 5: Check if dynamic reload is possible and get reason if not
        var unloadBlockedReason: String? = null
        if (existingPlugin != null) {
            val descriptor = existingPlugin as? IdeaPluginDescriptorImpl
            if (descriptor !== null) {
                @Suppress("UnstableApiUsage")
                unloadBlockedReason = DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)
                if (unloadBlockedReason != null) {
                    val warnMsg = "Plugin cannot be unloaded without restart: $unloadBlockedReason"
                    log.warn(warnMsg)
                    progress.report(warnMsg)
                }
            }
        }

        // Step 6: Unload existing plugin if it exists and is enabled
        var memoryDumpPath: String? = null
        if (existingPlugin != null && !PluginManagerCore.isDisabled(pluginId)) {
            val descriptor = existingPlugin as? IdeaPluginDescriptorImpl
            if (descriptor !== null) {
                progress.report(HotReloadBundle.message("progress.unloading", existingPlugin.name))

                @Suppress("UnstableApiUsage")
                val options = DynamicPlugins.UnloadPluginOptions(requireMemorySnapshot = unloadBlockedReason != null)

                // Mark plugin as not loading - this is done by unloadPlugins (plural) but not
                // by unloadPlugin (singular). Without this, the plugin remains in enabledPlugins
                // with isMarkedForLoading=true, causing assertion in PluginSet.withPlugin when
                // loading the new version.
                descriptor.isMarkedForLoading = false

                @Suppress("UnstableApiUsage")
                val unloaded = DynamicPlugins.unloadPlugin(descriptor, options)

                if (!unloaded) {
                    val warnMsg = HotReloadBundle.message("error.unload.failed")
                    log.warn(warnMsg)
                    progress.reportError(warnMsg)

                    // Create memory dump if unload failed
                    if (MemoryDumpHelper.memoryDumpAvailable()) {
                        memoryDumpPath = createMemoryDump(pluginIdString, progress)
                    }

                    // Return early - cannot proceed with loading new plugin
                    // when old plugin is still loaded (would cause assertion in PluginSet.withPlugin)
                    return ReloadResult(
                        success = false,
                        message = warnMsg,
                        pluginId = pluginIdString,
                        pluginName = existingPlugin.name,
                        restartRequired = true,
                        memoryDumpPath = memoryDumpPath,
                        unloadBlockedReason = unloadBlockedReason
                    )
                } else {
                    progress.report(HotReloadBundle.message("progress.unloaded"))
                }
            }
        }

        // Step 7: Delete old plugin folder (rename first for safety)
        if (existingPluginPath != null && Files.exists(existingPluginPath)) {
            progress.report(HotReloadBundle.message("progress.removing.old", existingPluginPath))
            try {
                val backupPath = existingPluginPath.resolveSibling("${existingPluginPath.fileName}.old.${System.currentTimeMillis()}")
                Files.move(existingPluginPath, backupPath)
                try {
                    NioFiles.deleteRecursively(backupPath)
                    progress.report(HotReloadBundle.message("progress.removed.old"))
                } catch (e: Exception) {
                    log.warn("Could not delete old plugin folder immediately: $backupPath", e)
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to remove old plugin: ${e.message}"
                log.warn("Failed to remove old plugin folder", e)
                progress.reportError(errorMsg)
                return ReloadResult(false, errorMsg, pluginIdString, memoryDumpPath = memoryDumpPath, unloadBlockedReason = unloadBlockedReason)
            }
        }

        // Step 8: Load descriptor from the zip file
        progress.report(HotReloadBundle.message("progress.loading.descriptor"))
        @Suppress("UnstableApiUsage")
        val newDescriptor = try {
            loadDescriptorFromArtifact(zipFile, null)
        } catch (e: Exception) {
            val errorMsg = HotReloadBundle.message("error.descriptor.load.failed")
            log.warn("Failed to load descriptor from zip", e)
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginIdString, memoryDumpPath = memoryDumpPath, unloadBlockedReason = unloadBlockedReason)
        }

        if (newDescriptor === null) {
            val errorMsg = HotReloadBundle.message("error.descriptor.load.failed")
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginIdString, memoryDumpPath = memoryDumpPath, unloadBlockedReason = unloadBlockedReason)
        }

        val pluginName = newDescriptor.name
        val pluginVersion = newDescriptor.version

        progress.report(HotReloadBundle.message("progress.installing", pluginName, pluginVersion ?: "unknown"))

        // Step 9: Install and load the plugin dynamically
        @Suppress("UnstableApiUsage")
        val loaded = try {
            PluginInstaller.installAndLoadDynamicPlugin(zipFile, null, newDescriptor as IdeaPluginDescriptorImpl)
        } catch (e: Exception) {
            val errorMsg = HotReloadBundle.message("error.install.failed")
            log.warn("Failed to install and load plugin", e)
            progress.reportError(errorMsg)
            return ReloadResult(false, errorMsg, pluginIdString, pluginName, restartRequired = true, memoryDumpPath = memoryDumpPath, unloadBlockedReason = unloadBlockedReason)
        }

        return if (loaded) {
            progress.report(HotReloadBundle.message("progress.reloaded", pluginName))
            ReloadResult(true, "Plugin reloaded successfully", pluginIdString, pluginName, memoryDumpPath = memoryDumpPath, unloadBlockedReason = unloadBlockedReason)
        } else {
            val failMsg = "Plugin installed but restart required"
            progress.reportError(failMsg)
            ReloadResult(false, failMsg, pluginIdString, pluginName, restartRequired = true, memoryDumpPath = memoryDumpPath, unloadBlockedReason = unloadBlockedReason)
        }
    }

    /**
     * Create a memory dump for debugging plugin unload issues.
     * Returns the path to the dump file.
     */
    private fun createMemoryDump(pluginId: String, progress: ProgressReporter): String? {
        return try {
            val snapshotDate = SimpleDateFormat("dd.MM.yyyy_HH.mm.ss").format(Date())
            val snapshotFileName = "unload-$pluginId-$snapshotDate.hprof"
            val snapshotDir = System.getProperty("memory.snapshots.path", System.getProperty("user.home"))
            val snapshotPath = "$snapshotDir/$snapshotFileName"

            progress.report("Creating memory dump at: $snapshotPath")
            MemoryDumpHelper.captureMemoryDump(snapshotPath)
            progress.report("Memory dump created: $snapshotFileName")
            log.info("Memory dump created at: $snapshotPath")

            snapshotPath
        } catch (e: Exception) {
            log.warn("Failed to create memory dump", e)
            progress.report("Failed to create memory dump: ${e.message}")
            null
        }
    }

    /**
     * Extract the plugin ID from plugin.xml inside the zip file.
     * Handles both flat structure (META-INF/plugin.xml) and nested jar structure
     * (plugin-name/lib/plugin-name.jar containing META-INF/plugin.xml).
     */
    internal fun extractPluginId(zipBytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                // Check for direct plugin.xml (flat structure)
                if (name.endsWith("/META-INF/plugin.xml") || name == "META-INF/plugin.xml") {
                    val xmlBytes = zis.readBytes()
                    return parsePluginIdFromXml(xmlBytes)
                }
                // Check for jars in lib folder (nested structure)
                if (name.contains("/lib/") && name.endsWith(".jar") && !entry.isDirectory) {
                    val jarBytes = zis.readBytes()
                    val pluginId = extractPluginIdFromJar(jarBytes)
                    if (pluginId != null) {
                        return pluginId
                    }
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * Extract plugin ID from a jar file that may contain META-INF/plugin.xml.
     */
    private fun extractPluginIdFromJar(jarBytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { jis ->
            var entry = jis.nextEntry
            while (entry != null) {
                if (entry.name == "META-INF/plugin.xml") {
                    val xmlBytes = jis.readBytes()
                    return parsePluginIdFromXml(xmlBytes)
                }
                entry = jis.nextEntry
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
            log.warn("Failed to parse plugin.xml", e)
            null
        }
    }
}


/**
 * Gets the plugin ID of this hot-reload plugin dynamically.
 * We cannot reload ourselves - the reload code would be unloaded mid-execution.
 *
 * Falls back to EXPECTED_PLUGIN_ID if dynamic lookup fails (e.g., during tests
 * or if the plugin classloader isn't properly set up).
 */
fun getSelfPluginId(): String {
    return PluginManager.getPluginByClass(PluginHotReloadService::class.java)
        ?.pluginId?.idString
        ?: "com.jonnyzzz.intellij.hot-reload"
}
