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
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    pluginConfiguration {
        name = "Plugin Hot Reload"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "253"
            untilBuild = null
        }
    }
    pluginVerification { ides { recommended() } }
}

tasks.test { useJUnit() }

// --- Hot Reload Gradle Tasks ---

data class HotReloadEndpoint(val url: String, val token: String, val ideInfo: String)

fun findHotReloadEndpoints(): List<HotReloadEndpoint> {
    val home = File(System.getProperty("user.home"))
    return home.listFiles { f -> f.name.matches(Regex("\\.\\d+\\.hot-reload")) }
        ?.mapNotNull { f ->
            val lines = f.readLines()
            if (lines.size >= 3) HotReloadEndpoint(lines[0], lines[1], lines.drop(4).firstOrNull() ?: "") else null
        }
        ?.distinctBy { it.url }
        ?: emptyList()
}

fun deployToEndpoint(endpoint: HotReloadEndpoint, zip: File): Boolean {
    val conn = (URI(endpoint.url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Authorization", endpoint.token)
        setRequestProperty("Content-Type", "application/octet-stream")
        connectTimeout = 5000
        readTimeout = 300000
    }

    conn.outputStream.use { out -> zip.inputStream().use { it.copyTo(out) } }

    if (conn.responseCode !in 200..299) {
        println("  ✗ HTTP ${conn.responseCode}")
        return false
    }

    var lastLine = ""
    conn.inputStream.bufferedReader().forEachLine { line ->
        println("  $line")
        lastLine = line
    }

    return lastLine == "SUCCESS"
}

val deployPlugin by tasks.registering {
    group = "intellij platform"
    description = "Deploy plugin to running IDEs with hot-reload"
    dependsOn(tasks.named("buildPlugin"))

    doLast {
        val zip = tasks.named("buildPlugin").get().outputs.files.singleFile
        val endpoints = findHotReloadEndpoints()

        if (endpoints.isEmpty()) {
            println("No hot-reload endpoints found in ~")
            return@doLast
        }

        var ok = 0
        var fail = 0
        endpoints.forEach { ep ->
            println("\n${ep.ideInfo.ifEmpty { ep.url }}")
            if (deployToEndpoint(ep, zip)) ok++ else fail++
        }
        println("\nDone: $ok ok, $fail failed")
        if (fail > 0 && ok == 0) throw GradleException("All deployments failed")
    }
}

val listHotReloadEndpoints by tasks.registering {
    group = "intellij platform"
    description = "List running IDEs with hot-reload"
    doLast {
        findHotReloadEndpoints().forEach { println("${it.ideInfo.ifEmpty { it.url }}") }
    }
}
