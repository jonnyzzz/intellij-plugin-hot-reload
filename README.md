# IntelliJ Plugin Hot Reload

An IntelliJ IDEA plugin that provides an HTTP REST endpoint for dynamically reloading plugins without IDE restart.

## Features

- **HTTP REST Endpoint** (`/api/plugin-hot-reload`):
  - `GET`: Returns this README documentation
  - `POST`: Accepts a plugin .zip file and performs hot reload with streaming progress output

- **Streaming Progress**: POST responses stream progress updates in real-time using chunked transfer encoding

- **IDE Notifications**: Shows balloon notifications in the IDE for reload progress and results

- **Process Discovery**: Creates a marker file `.<pid>.hot-reload` in the user's home directory for external tools to discover running IDE instances

- **Authentication**: POST requests require a Bearer token that's stored in the marker file

- **Nested Jar Support**: Extracts plugin ID from both flat structure (`META-INF/plugin.xml`) and IntelliJ's standard nested jar structure (`plugin-name/lib/plugin-name.jar` containing `META-INF/plugin.xml`)

- **Self-Reload Prevention**: The hot-reload plugin cannot reload itself (the reload code would be unloaded mid-execution). Attempting to do so returns a clear error message.

- **Gradle Integration**: Provides `deployPlugin` task for plugin developers using IntelliJ Platform Gradle Plugin

## Requirements

- IntelliJ IDEA 2025.3 or later (build 253+)
- Java 21+

## Internal API Usage 

The plugin is heavy on internal API usage. It may break in the next releases of IntelliJ.
See and join the discussion https://youtrack.jetbrains.com/issue/IJPL-224753/Provide-API-to-dynamically-reload-a-plugin


## Installation

1. Download the plugin zip from [Releases](https://github.com/jonnyzzz/intellij-plugin-hot-reload/releases)
2. In IntelliJ: Settings > Plugins > Install Plugin from Disk
3. Restart IDE

## Usage

### For Plugin Developers (Recommended)

If you're developing an IntelliJ plugin using the IntelliJ Platform Gradle Plugin, add this task to your `build.gradle.kts`:

```kotlin
import java.net.HttpURLConnection
import java.net.URI

val deployPlugin by tasks.registering {
    group = "intellij platform"
    description = "Deploy plugin to running IDEs"
    dependsOn(tasks.named("buildPlugin"))
    doLast {
        val zip = tasks.named("buildPlugin").get().outputs.files.singleFile
        val home = File(System.getProperty("user.home"))
        val endpoints = home.listFiles { f -> f.name.matches(Regex("\\.\\d+\\.hot-reload")) }
            ?.mapNotNull { f ->
                val pid = Regex("\\.(\\d+)\\.").find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                if (!ProcessHandle.of(pid).isPresent) return@mapNotNull null
                val lines = f.readLines().takeIf { it.size >= 2 } ?: return@mapNotNull null
                lines[0] to lines[1]
            }?.distinctBy { it.first } ?: emptyList()

        if (endpoints.isEmpty()) { println("No running IDEs found"); return@doLast }

        endpoints.forEach { (url, token) ->
            println("\n→ $url")
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", token)
                setRequestProperty("Content-Type", "application/octet-stream")
                connectTimeout = 5000; readTimeout = 300000
            }
            conn.outputStream.use { out -> zip.inputStream().use { it.copyTo(out) } }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().forEachLine { println("  $it") }
            } else {
                println("  ✗ HTTP ${conn.responseCode}")
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

#### Get Documentation

```bash
curl http://localhost:63342/api/plugin-hot-reload
```

Returns this README as `text/markdown`.

#### Hot Reload a Plugin

First, get the token from the marker file:

```bash
# Find the marker file (PID varies)
ls ~/.*hot-reload

# Read the token (second line)
TOKEN=$(sed -n '2p' ~/.<pid>.hot-reload)

# Deploy the plugin with streaming output
# -N disables buffering for real-time streaming
curl -N -X POST -H "Authorization: $TOKEN" --data-binary @my-plugin.zip \
  http://localhost:63342/api/plugin-hot-reload
```

**Important**: Use `curl -N` (or `--no-buffer`) to see streaming output in real-time. Without it, curl buffers the response and only shows output when complete.

Example streaming output:
```
Starting plugin hot reload, zip size: 7,601,343 bytes
Extracting plugin ID from zip...
Plugin ID: com.example.my-plugin
Looking for existing plugin...
Existing plugin: My Plugin, path: /path/to/plugins/my-plugin
Unloading existing plugin: My Plugin
Plugin unloaded successfully
Removing old plugin at: /path/to/plugins/my-plugin
Old plugin folder removed
Loading plugin descriptor from zip...
Installing and loading plugin: My Plugin (1.0.0)
Plugin My Plugin reloaded successfully
SUCCESS
```

### Response Format

The POST endpoint returns a streaming `text/plain` response with chunked transfer encoding. Progress messages appear one per line as each step completes.

The last line is always either `SUCCESS` or `FAILED`.

Error messages are prefixed with `ERROR: `.

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

The plugin reload process:

1. **Extract plugin ID** from the uploaded zip file
   - First checks for `META-INF/plugin.xml` at the top level (flat structure)
   - If not found, searches inside jars in `*/lib/*.jar` for `META-INF/plugin.xml` (nested structure)
   - This handles both development builds and production IntelliJ plugin zips

2. **Check for self-reload** - if the plugin ID matches the hot-reload plugin itself, reject with error (cannot reload ourselves)

3. **Find existing plugin** by ID using `PluginManagerCore.getPlugin(pluginId)`

4. **Check if dynamic unload is possible** using `DynamicPlugins.checkCanUnloadWithoutRestart()`

5. **Unload existing plugin** using `DynamicPlugins.unloadPlugin()`

6. **Delete old plugin folder** (renames to `.old.<timestamp>` first for safety, then deletes)

7. **Load plugin descriptor** from the zip using `loadDescriptorFromArtifact()`

8. **Install and load** the new plugin using `PluginInstaller.installAndLoadDynamicPlugin()`

Progress is streamed to the HTTP response in real-time using chunked transfer encoding, and also shown as IDE balloon notifications.

**Note**: Not all plugins support dynamic reload. If dynamic reload fails, an IDE restart will be required.

## Limitations

- **Cannot reload itself**: The hot-reload plugin cannot reload itself. To update the hot-reload plugin, restart the IDE.
- **Not all plugins support dynamic reload**: Some plugins have extensions or services that prevent unloading. The plugin will report this and may require an IDE restart.
- **Memory leaks possible**: If a plugin doesn't properly clean up resources, memory leaks may occur after reload.

## Building

```bash
# Build the plugin
./gradlew build

# Run tests
./gradlew test

# Run specific tests
./gradlew test --tests "*PluginHotReloadServiceTest*"

# Run plugin in a sandboxed IntelliJ instance
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin

# Deploy to all running IDEs
./gradlew deployPlugin
```

The built plugin will be in `build/distributions/`.

## Development

### Project Structure

```
src/main/kotlin/com/jonnyzzz/intellij/hotreload/
├── HotReloadBundle.kt           # Message bundle for i18n
├── HotReloadHttpHandler.kt      # HTTP REST endpoint with streaming response
├── HotReloadMarkerService.kt    # Marker file management (Disposable)
├── HotReloadNotifications.kt    # IDE balloon notifications
├── HotReloadStartupActivity.kt  # Startup trigger for marker service
└── PluginHotReloadService.kt    # Plugin reload business logic with progress callbacks

src/main/resources/
├── META-INF/plugin.xml          # Plugin descriptor
├── messages/HotReloadBundle.properties  # Localized messages
└── hot-reload/README.md         # This file (served via GET endpoint)

src/test/kotlin/com/jonnyzzz/intellij/hotreload/
├── MarkerFileTest.kt            # Marker file tests
└── PluginHotReloadServiceTest.kt # Plugin ID extraction and reload tests
```

### Key APIs Used

- `DynamicPlugins.checkCanUnloadWithoutRestart()` - Check if plugin supports hot reload
- `DynamicPlugins.unloadPlugin()` - Unload a plugin
- `PluginInstaller.installAndLoadDynamicPlugin()` - Install and load a plugin
- `loadDescriptorFromArtifact()` - Load plugin descriptor from zip file
- `PluginManager.getPluginByClass()` - Get plugin descriptor for a class (used for self-detection)
- `NotificationGroupManager` - IDE balloon notifications

### Key Implementation Details

#### Plugin ID Extraction

The `extractPluginId()` function handles two plugin zip structures:

1. **Flat structure** (development builds):
   ```
   plugin-name/META-INF/plugin.xml
   ```

2. **Nested jar structure** (production builds from `buildPlugin`):
   ```
   plugin-name/lib/plugin-name.jar
     └── META-INF/plugin.xml
   ```

#### Self-Reload Detection

The plugin dynamically detects its own ID using:
```kotlin
fun getSelfPluginId(): String {
    return PluginManager.getPluginByClass(PluginHotReloadService::class.java)
        ?.pluginId?.idString
        ?: "com.jonnyzzz.intellij.hot-reload"  // fallback for tests
}
```

## Security

- Authentication is required for POST requests
- Each IDE instance generates a unique Bearer token on startup
- The token is stored in the marker file (only readable by the user)
- Marker files are automatically deleted when the IDE exits

## License

Apache 2.0
