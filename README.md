# IntelliJ Plugin Hot Reload

An IntelliJ IDEA plugin that provides an HTTP REST endpoint for dynamically reloading plugins without IDE restart.

## Features

- **HTTP REST Endpoint** (`/api/plugin-hot-reload`):
  - `GET`: Returns usage instructions and current process ID
  - `POST`: Accepts a plugin .zip file and performs hot reload

- **Process Discovery**: Creates a marker file `.<pid>.hot-reload` in the user's home directory for external tools to discover running IDE instances

## Requirements

- IntelliJ IDEA 2025.3 or later (build 253+)
- Java 21+

## Installation

1. Download the plugin zip from [Releases](https://github.com/jonnyzzz/intellij-plugin-hot-reload/releases)
2. In IntelliJ: Settings > Plugins > Install Plugin from Disk
3. Restart IDE

## Usage

### Get Instructions

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
    "POST": "Upload a plugin .zip file to hot-reload it"
  },
  "example": "curl -X POST --data-binary @plugin.zip http://localhost:63342/api/plugin-hot-reload"
}
```

### Hot Reload a Plugin

```bash
curl -X POST --data-binary @my-plugin.zip http://localhost:63342/api/plugin-hot-reload
```

The plugin will:
1. Extract the plugin ID from `META-INF/plugin.xml` in the zip
2. Unload the existing plugin with that ID (if present)
3. Remove the old plugin folder
4. Install the new plugin from the zip
5. Load the new plugin dynamically

**Note**: Not all plugins support dynamic reload. If dynamic reload fails, an IDE restart will be required.

### Process Discovery

The plugin creates a marker file in the user's home directory:

```
~/.{pid}.hot-reload
```

External tools can use this to discover running IDE instances with the hot-reload plugin installed.

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
```

The built plugin will be in `build/distributions/`.

## Development

### Project Structure

```
src/main/kotlin/com/jonnyzzz/intellij/hotreload/
├── HotReloadHttpHandler.kt      # HTTP REST endpoint handler
├── HotReloadMarkerService.kt    # Marker file management (Disposable)
├── HotReloadStartupActivity.kt  # Startup trigger for marker service
└── PluginHotReloadService.kt    # Plugin reload business logic
```

### Key APIs Used

- `DynamicPlugins.allowLoadUnloadWithoutRestart()` - Check if plugin supports hot reload
- `PluginInstaller.unloadDynamicPlugin()` - Unload a plugin
- `PluginInstaller.installAndLoadDynamicPlugin()` - Install and load a plugin
- `loadDescriptorFromArtifact()` - Load plugin descriptor from zip file

## License

Apache 2.0
