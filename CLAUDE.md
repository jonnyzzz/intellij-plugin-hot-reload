# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

IntelliJ Plugin Hot Reload - a plugin that provides an HTTP REST endpoint for dynamically reloading plugins without IDE restart.

## Build Commands

```bash
# Build the plugin
./gradlew build

# Run plugin in a sandboxed IntelliJ instance
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin

# Run tests
./gradlew test
```

## Technology Stack

- **Gradle**: 9.2.1 with Kotlin DSL
- **Kotlin**: 2.2.21
- **Java Toolchain**: 21
- **IntelliJ Platform**: 2025.3+ (sinceBuild: 253)
- **IntelliJ Platform Gradle Plugin**: 2.10.5

## Architecture

### HTTP REST Endpoint

The plugin registers an HTTP request handler at `/api/plugin-hot-reload`:

- **GET**: Returns usage instructions
- **POST**: Accepts a plugin zip file, unloads the existing plugin, replaces it, and reloads dynamically

### Marker File

On IDE startup, creates `.<pid>.hot-reload` file in user home directory. This file is removed when the IDE exits. Used for process discovery by external tools.

## Source Structure

```
src/main/kotlin/com/jonnyzzz/intellij/hotreload/
├── HotReloadHttpHandler.kt      # HTTP REST endpoint handler
├── HotReloadStartupActivity.kt  # Marker file management
└── PluginHotReloadService.kt    # Plugin reload business logic
```

## Key IntelliJ Platform APIs Used

### Plugin Loading/Unloading

```kotlin
// Check if plugin can be dynamically loaded/unloaded
DynamicPlugins.allowLoadUnloadWithoutRestart(pluginDescriptor)

// Unload a plugin dynamically
PluginInstaller.unloadDynamicPlugin(parentComponent, pluginDescriptor, isUpdate)

// Install and load a plugin from file
PluginInstaller.installAndLoadDynamicPlugin(file, parent, descriptor)

// Unpack plugin zip to plugins folder
PluginInstaller.unpackPlugin(sourceFile, targetPath)

// Delete plugin folder
NioFiles.deleteRecursively(pluginDescriptor.getPluginPath())
```

### Plugin Descriptor Loading

```kotlin
// Load plugin descriptor from zip/jar file
PluginDescriptorLoader.loadDescriptorFromArtifact(file, null)

// Load plugin descriptor from extracted folder
PluginDescriptorLoader.loadDescriptor(targetFile, false, PluginXmlPathResolver.DEFAULT_PATH_RESOLVER)

// Find plugin by ID
PluginManagerCore.getPlugin(pluginId)
PluginManagerCore.findPlugin(pluginId)
```

### HTTP Request Handler

The plugin uses `HttpRequestHandler` extension point:

```kotlin
class HotReloadHttpHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean
    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean
}
```

Key utilities:
- `HttpRequestHandler.checkPrefix(uri, prefix)` - check URI prefix
- `response(contentType, content)` - create HTTP response
- `response.send(channel, request)` - send response

### Process ID

```kotlin
val pid = ProcessHandle.current().pid()
```

### Application Lifecycle

```kotlin
// Register cleanup on application disposal
Disposer.register(ApplicationManager.getApplication(), Disposable {
    // cleanup code
})
```

## Hot Reload Flow

1. **Receive POST request** with plugin zip file
2. **Parse plugin.xml** from zip to extract plugin ID
3. **Find existing plugin** by ID using `PluginManagerCore.findPlugin(pluginId)`
4. **Check if dynamic unload is possible** using `DynamicPlugins.allowLoadUnloadWithoutRestart()`
5. **Unload existing plugin** using `PluginInstaller.unloadDynamicPlugin()`
6. **Delete old plugin folder** using `NioFiles.deleteRecursively()`
7. **Unpack new plugin** using `PluginInstaller.unpackPlugin()`
8. **Load new plugin** using `PluginInstaller.installAndLoadDynamicPlugin()`

## Testing

Tests use IntelliJ Platform test framework:

```kotlin
class HotReloadTest : BasePlatformTestCase() {
    fun testMarkerFileCreation() = runBlocking {
        // test code
    }
}
```

Run specific test:
```bash
./gradlew test --tests "*HotReloadTest*"
```

## plugin.xml Extensions

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- HTTP REST API endpoint -->
    <httpRequestHandler
        implementation="com.jonnyzzz.intellij.hotreload.HotReloadHttpHandler"/>

    <!-- Startup activity to create marker file -->
    <postStartupActivity
        implementation="com.jonnyzzz.intellij.hotreload.HotReloadStartupActivity"/>
</extensions>
```

## Committing Changes

```bash
git add -A
git commit -m "$(cat <<'EOF'
Brief description

- Detail 1
- Detail 2

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```
