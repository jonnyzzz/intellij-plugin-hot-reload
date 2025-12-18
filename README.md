# IntelliJ Plugin Hot Reload

An IntelliJ IDEA plugin that provides an HTTP REST endpoint for dynamically reloading plugins without IDE restart.

## Features

- **HTTP REST Endpoint** (`/api/plugin-hot-reload`):
  - `GET`: Returns usage instructions and current process ID
  - `POST`: Accepts a plugin .zip file and performs hot reload with streaming progress output

- **Streaming Progress**: POST responses stream progress updates in real-time, showing each step of the reload process

- **IDE Notifications**: Shows balloon notifications in the IDE for reload progress and results

- **Process Discovery**: Creates a marker file `.<pid>.hot-reload` in the user's home directory for external tools to discover running IDE instances

- **Authentication**: POST requests require a Bearer token that's stored in the marker file

- **Gradle Integration**: Provides `deployPlugin` task for plugin developers using IntelliJ Platform Gradle Plugin

## Requirements

- IntelliJ IDEA 2025.3 or later (build 253+)
- Java 21+

## Installation

1. Download the plugin zip from [Releases](https://github.com/jonnyzzz/intellij-plugin-hot-reload/releases)
2. In IntelliJ: Settings > Plugins > Install Plugin from Disk
3. Restart IDE

## Usage

### For Plugin Developers (Recommended)

If you're developing an IntelliJ plugin using the IntelliJ Platform Gradle Plugin, add the hot-reload Gradle tasks to your `build.gradle.kts`:

```kotlin
import java.net.HttpURLConnection
import java.net.URI

// Data class for hot-reload endpoints
data class HotReloadEndpoint(
    val url: String,
    val token: String,
    val dateTime: String,
    val ideInfo: String,
    val markerFile: File
)

// Function to find hot-reload endpoints
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
            } else null
        } catch (e: Exception) { null }
    }.distinctBy { it.url }
}

// Function to deploy with streaming output
fun deployToEndpoint(endpoint: HotReloadEndpoint, pluginZip: File): Boolean {
    return try {
        val url = URI(endpoint.url).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", endpoint.token)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.connectTimeout = 5000
        connection.readTimeout = 300000

        connection.outputStream.use { output ->
            pluginZip.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        if (connection.responseCode !in 200..299) {
            println("Failed: ${connection.responseCode}")
            return false
        }

        // Stream progress output
        var success = false
        connection.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("INFO: ") -> println("  ${line.removePrefix("INFO: ")}")
                    line.startsWith("ERROR: ") -> println("  ✗ ${line.removePrefix("ERROR: ")}")
                    line.startsWith("RESULT: SUCCESS") -> success = true
                    line.startsWith("RESULT: FAILED") -> success = false
                }
            }
        }
        success
    } catch (e: Exception) {
        println("Error: ${e.message}")
        false
    }
}

// Deploy plugin task
val deployPlugin by tasks.registering {
    group = "intellij platform"
    description = "Deploy plugin to all running IDE instances with hot-reload support"
    dependsOn(tasks.named("buildPlugin"))

    doLast {
        val pluginZip = tasks.named("buildPlugin").get().outputs.files.singleFile
        val endpoints = findHotReloadEndpoints()

        if (endpoints.isEmpty()) {
            println("No hot-reload endpoints found. Start an IDE with the Plugin Hot Reload plugin installed.")
            return@doLast
        }

        println("Deploying to ${endpoints.size} endpoint(s)...")
        endpoints.forEach { endpoint ->
            println("\nDeploying to: ${endpoint.url}")
            if (deployToEndpoint(endpoint, pluginZip)) {
                println("✓ Deployed successfully")
            } else {
                println("✗ Deployment failed")
            }
        }
    }
}
```

Then run:

```bash
./gradlew deployPlugin
```

This will:
1. Build your plugin
2. Find all running IDEs with the hot-reload plugin installed
3. Deploy your plugin to each IDE with streaming progress output

### Manual Usage with curl

#### Get Instructions

```bash
curl http://localhost:63342/api/plugin-hot-reload
```

Response:
```json
{
  "status": "ok",
  "pid": 12345,
  "usage": {
    "GET": "Returns this information",
    "POST": "Upload a plugin .zip file to hot-reload it (requires Authorization header)"
  },
  "authentication": "POST requires 'Authorization: Bearer <token>' header. Token is in the marker file.",
  "response": "POST returns streaming text/plain with progress updates, one message per line",
  "example": "curl -X POST -H 'Authorization: Bearer <token>' --data-binary @plugin.zip http://localhost:<port>/api/plugin-hot-reload"
}
```

#### Hot Reload a Plugin

First, get the token from the marker file:

```bash
# Find the marker file
ls ~/.*hot-reload

# Read the token (second line)
TOKEN=$(sed -n '2p' ~/.<pid>.hot-reload)

# Deploy the plugin with streaming output
curl -X POST -H "Authorization: $TOKEN" --data-binary @my-plugin.zip http://localhost:63342/api/plugin-hot-reload
```

Example streaming output:
```
INFO: Starting plugin hot reload, zip size: 123456 bytes
INFO: Extracting plugin ID from zip...
INFO: Plugin ID: com.example.my-plugin
INFO: Looking for existing plugin...
INFO: Existing plugin: My Plugin, path: /path/to/plugins/my-plugin
INFO: Unloading existing plugin: My Plugin
INFO: Plugin unloaded successfully
INFO: Removing old plugin at: /path/to/plugins/my-plugin
INFO: Old plugin folder removed
INFO: Loading plugin descriptor from zip...
INFO: Installing and loading plugin: My Plugin (1.0.0)
INFO: Plugin My Plugin reloaded successfully

RESULT: SUCCESS
PLUGIN: My Plugin
```

### Response Format

The POST endpoint returns a streaming `text/plain` response with one message per line:

| Prefix | Description |
|--------|-------------|
| `INFO: ` | Progress message |
| `ERROR: ` | Error message |
| `RESULT: SUCCESS` | Reload completed successfully |
| `RESULT: FAILED` | Reload failed |
| `RESULT: RESTART_REQUIRED` | Plugin installed but IDE restart needed |
| `PLUGIN: ` | Plugin name |
| `MESSAGE: ` | Additional status message |

### Marker File Format

The plugin creates a marker file at `~/.<pid>.hot-reload` with the following format:

```
http://localhost:63342/api/plugin-hot-reload
Bearer <random-uuid-token>
2024-01-15T10:30:00+01:00

IntelliJ IDEA 2025.3
Build #IU-253.12345
Built on January 1, 2025

Runtime version: 21.0.1+12-39
VM: OpenJDK 64-Bit Server VM by JetBrains s.r.o.

OS: Mac OS X 14.0 (aarch64)
GC: G1 Young Generation, G1 Old Generation
Memory: 2048 MB
```

- **Line 1**: POST URL for hot-reload endpoint
- **Line 2**: Bearer token for authentication
- **Line 3**: Marker file creation timestamp
- **Line 4+**: IDE information (similar to Help > About > Copy)

## How It Works

The plugin will:
1. Extract the plugin ID from `META-INF/plugin.xml` in the zip
2. Unload the existing plugin with that ID (if present)
3. Remove the old plugin folder
4. Install the new plugin from the zip
5. Load the new plugin dynamically

Progress is streamed to the HTTP response in real-time and also shown as IDE balloon notifications.

**Note**: Not all plugins support dynamic reload. If dynamic reload fails, an IDE restart will be required.

## Building

```bash
# Build the plugin
./gradlew build

# Run tests
./gradlew test

# Run plugin in a sandboxed IntelliJ instance
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin

# List discovered hot-reload endpoints
./gradlew listHotReloadEndpoints

# Deploy to all running IDEs
./gradlew deployPlugin
```

The built plugin will be in `build/distributions/`.

## Development

### Project Structure

```
src/main/kotlin/com/jonnyzzz/intellij/hotreload/
├── HotReloadHttpHandler.kt      # HTTP REST endpoint with streaming response
├── HotReloadMarkerService.kt    # Marker file management (Disposable)
├── HotReloadNotifications.kt    # IDE balloon notifications
├── HotReloadStartupActivity.kt  # Startup trigger for marker service
└── PluginHotReloadService.kt    # Plugin reload business logic with progress callbacks
```

### Key APIs Used

- `DynamicPlugins.allowLoadUnloadWithoutRestart()` - Check if plugin supports hot reload
- `PluginInstaller.unloadDynamicPlugin()` - Unload a plugin
- `PluginInstaller.installAndLoadDynamicPlugin()` - Install and load a plugin
- `loadDescriptorFromArtifact()` - Load plugin descriptor from zip file
- `NotificationGroupManager` - IDE balloon notifications

## Security

- Authentication is required for POST requests
- Each IDE instance generates a unique Bearer token on startup
- The token is stored in the marker file (only readable by the user)
- Marker files are automatically deleted when the IDE exits

## License

Apache 2.0
