import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("org.jetbrains.intellij.platform") version "2.10.5"
    kotlin("jvm") version "2.2.21"
}

group = "com.jonnyzzz.intellij"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = "Plugin Hot Reload"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = null
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}

/**
 * Data class representing a hot-reload endpoint discovered from a marker file.
 */
data class HotReloadEndpoint(
    val url: String,
    val token: String,
    val dateTime: String,
    val ideInfo: String,
    val markerFile: File
)

/**
 * Scans for hot-reload marker files in the user's home directory.
 * Returns a list of discovered endpoints, filtered for duplicates by URL.
 */
fun findHotReloadEndpoints(): List<HotReloadEndpoint> {
    val userHome = File(System.getProperty("user.home"))
    val markerFiles = userHome.listFiles { file ->
        file.name.matches(Regex("\\.\\d+\\.hot-reload"))
    } ?: emptyArray()

    return markerFiles.mapNotNull { file ->
        try {
            val lines = file.readLines()
            if (lines.size >= 3) {
                HotReloadEndpoint(
                    url = lines[0].trim(),
                    token = lines[1].trim(),
                    dateTime = lines[2].trim(),
                    ideInfo = lines.drop(4).joinToString("\n").trim(),
                    markerFile = file
                )
            } else {
                println("Warning: Marker file ${file.name} has invalid format (less than 3 lines)")
                null
            }
        } catch (e: Exception) {
            println("Warning: Failed to read marker file ${file.name}: ${e.message}")
            null
        }
    }.distinctBy { it.url }
}

/**
 * Deploys a plugin zip file to a hot-reload endpoint.
 * Returns true if successful, false otherwise.
 */
fun deployToEndpoint(endpoint: HotReloadEndpoint, pluginZip: File): Boolean {
    return try {
        val url = URI(endpoint.url).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", endpoint.token)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.connectTimeout = 5000
        connection.readTimeout = 30000

        connection.outputStream.use { output ->
            pluginZip.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        val responseCode = connection.responseCode
        val responseMessage = try {
            connection.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            connection.errorStream?.bufferedReader()?.readText() ?: e.message
        }

        if (responseCode in 200..299) {
            println("  ✓ Successfully deployed to ${endpoint.url}")
            println("    Response: $responseMessage")
            true
        } else {
            println("  ✗ Failed to deploy to ${endpoint.url}")
            println("    Response code: $responseCode")
            println("    Response: $responseMessage")
            false
        }
    } catch (e: Exception) {
        println("  ✗ Failed to connect to ${endpoint.url}")
        println("    Error: ${e.message}")
        false
    }
}

/**
 * Task to deploy the plugin to all discovered IDE instances with the hot-reload plugin installed.
 *
 * Usage: ./gradlew deployPlugin
 *
 * This task:
 * 1. Builds the plugin zip file
 * 2. Scans for marker files (.<pid>.hot-reload) in user home directory
 * 3. Deploys to all discovered endpoints
 * 4. Reports success/failure for each endpoint
 */
val deployPlugin by tasks.registering {
    group = "intellij platform"
    description = "Deploy plugin to all running IDE instances with hot-reload support"
    dependsOn(tasks.named("buildPlugin"))

    doLast {
        val pluginZip = tasks.named("buildPlugin").get().outputs.files.singleFile

        println("Plugin zip: $pluginZip")
        println()

        val endpoints = findHotReloadEndpoints()

        if (endpoints.isEmpty()) {
            println("No hot-reload endpoints found.")
            println("Make sure you have an IDE running with the Plugin Hot Reload plugin installed.")
            println("Marker files should be in: ${System.getProperty("user.home")}")
            return@doLast
        }

        println("Found ${endpoints.size} hot-reload endpoint(s):")
        endpoints.forEach { endpoint ->
            println()
            println("  URL: ${endpoint.url}")
            println("  IDE: ${endpoint.ideInfo.lines().firstOrNull() ?: "Unknown"}")
            println("  Started: ${endpoint.dateTime}")
        }

        println()
        println("Deploying plugin...")

        var successCount = 0
        var failureCount = 0

        endpoints.forEach { endpoint ->
            println()
            println("Deploying to: ${endpoint.url}")
            if (deployToEndpoint(endpoint, pluginZip)) {
                successCount++
            } else {
                failureCount++
            }
        }

        println()
        println("Deployment complete: $successCount successful, $failureCount failed")

        if (failureCount > 0 && successCount == 0) {
            throw GradleException("All deployments failed")
        }
    }
}

/**
 * Task to list all discovered hot-reload endpoints without deploying.
 *
 * Usage: ./gradlew listHotReloadEndpoints
 */
val listHotReloadEndpoints by tasks.registering {
    group = "intellij platform"
    description = "List all discovered IDE instances with hot-reload support"

    doLast {
        val endpoints = findHotReloadEndpoints()

        if (endpoints.isEmpty()) {
            println("No hot-reload endpoints found.")
            println("Make sure you have an IDE running with the Plugin Hot Reload plugin installed.")
            println("Marker files should be in: ${System.getProperty("user.home")}")
            return@doLast
        }

        println("Found ${endpoints.size} hot-reload endpoint(s):")
        endpoints.forEach { endpoint ->
            println()
            println("=" .repeat(60))
            println("URL: ${endpoint.url}")
            println("Marker file: ${endpoint.markerFile.name}")
            println("Started: ${endpoint.dateTime}")
            println()
            println("IDE Information:")
            endpoint.ideInfo.lines().forEach { line ->
                println("  $line")
            }
        }
    }
}
