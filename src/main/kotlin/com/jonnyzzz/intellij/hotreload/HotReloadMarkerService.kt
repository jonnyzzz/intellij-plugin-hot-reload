package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import org.jetbrains.ide.BuiltInServerManager
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Application-level service that manages the hot-reload marker file.
 *
 * Creates .<pid>.hot-reload file in user home on init,
 * removes it when the service is disposed (on IDE exit).
 *
 * Marker file format:
 * - Line 1: POST URL (e.g., http://localhost:63342/api/plugin-hot-reload)
 * - Line 2: Bearer token for authentication
 * - Line 3: Creation date/time (ISO format)
 * - Line 4+: IDE information (similar to About -> Copy)
 */
@Service(Service.Level.APP)
class HotReloadMarkerService : Disposable {
    private val log = thisLogger()

    /**
     * The authentication token for this session.
     * Must match the Authorization header in HTTP requests.
     */
    val authToken: String = UUID.randomUUID().toString()

    fun createFiles() {
        val pid = ProcessHandle.current().pid()
        val userHome = System.getProperty("user.home")
        val markerFile = Path.of(userHome, ".$pid.hot-reload")

        // Get the built-in server port
        val port = BuiltInServerManager.getInstance().port
        val url = "http://localhost:$port/api/plugin-hot-reload"

        // Get current date/time
        val dateTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        // Get IDE info
        val ideInfo = buildIdeInfo()

        // Build marker file content
        val content = buildString {
            appendLine(url)
            appendLine("Bearer $authToken")
            appendLine(dateTime)
            appendLine()
            append(ideInfo)
        }

        runCatching {
            Files.writeString(markerFile, content)
        }

        log.info("Created hot-reload marker file: $markerFile")
        Disposer.register(this) {
            runCatching {
                Files.deleteIfExists(markerFile)
            }
        }
    }

    private fun buildIdeInfo(): String = buildString {
        val appInfo = ApplicationInfo.getInstance()
        val namesInfo = ApplicationNamesInfo.getInstance()

        // Product name and version
        appendLine("${namesInfo.fullProductName} ${appInfo.fullVersion}")
        appendLine("Build #${appInfo.build.asString()}")

        // Build date
        val buildDate = appInfo.buildDate
        if (buildDate != null) {
            val formatter = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
            appendLine("Built on ${formatter.format(buildDate.time)}")
        }

        // Runtime info
        appendLine()
        appendLine("Runtime version: ${System.getProperty("java.runtime.version", "unknown")}")
        appendLine("VM: ${System.getProperty("java.vm.name", "unknown")} by ${System.getProperty("java.vm.vendor", "unknown")}")

        // OS info
        appendLine()
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")

        // GC info
        val gcInfo = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
            .joinToString(", ") { it.name }
        if (gcInfo.isNotBlank()) {
            appendLine("GC: $gcInfo")
        }

        // Memory info
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        appendLine("Memory: $maxMemoryMb MB")
    }

    override fun dispose() = Unit
}
