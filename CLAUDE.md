# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## MUST DO
Use IntelliJ for everything where you can, it makes you 100x professional!

You must never ignore warnings or errors, instead, figure out
how to fix that the right way.

We love tests, and tests must show the reality. A failing test is much must
better than a test that does not check the problems. Make sure you fix tests.

Check you changes to see if there are no new warnings added to the code.


## Project Overview

IntelliJ Plugin Hot Reload - a plugin that provides an HTTP REST endpoint for dynamically reloading plugins without IDE restart.

**Key limitation**: This plugin cannot reload itself. Attempting to do so will be rejected with a clear error message.

## Key Documentation

- [AGENT-STEROID.md](AGENT-STEROID.md) - **IntelliJ API usage guide for LLM agents** - read this to become a power user

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

# Run specific tests
./gradlew test --tests "*PluginHotReloadServiceTest*"
./gradlew test --tests "*MarkerFileTest*"

# Deploy to running IDEs via hot-reload
./gradlew deployPlugin
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

- **GET**: Returns README.md content as `text/markdown`
- **POST**: Accepts a plugin zip file with streaming progress response

The POST endpoint uses chunked transfer encoding to stream progress in real-time.

### Marker File

On IDE startup, creates `.<pid>.hot-reload` file in user home directory containing:
- Line 1: HTTP endpoint URL
- Line 2: Bearer token for authentication
- Line 3: Timestamp
- Line 4+: IDE information

The file is removed when the IDE exits. Used for process discovery by external tools.

### Plugin ID Extraction

The `extractPluginId()` function handles two plugin zip structures:

1. **Flat structure**: `plugin-name/META-INF/plugin.xml`
2. **Nested jar structure**: `plugin-name/lib/plugin-name.jar` containing `META-INF/plugin.xml`

This is implemented in `PluginHotReloadService.kt` with:
- `extractPluginId(zipBytes)` - main entry point
- `extractPluginIdFromJar(jarBytes)` - searches inside jar files

### Self-Reload Prevention

The plugin detects attempts to reload itself using:

```kotlin
fun getSelfPluginId(): String {
    return PluginManager.getPluginByClass(PluginHotReloadService::class.java)
        ?.pluginId?.idString
        ?: "com.jonnyzzz.intellij.hot-reload"  // fallback for tests
}
```

If the extracted plugin ID matches `getSelfPluginId()`, the reload is rejected with error message from `HotReloadBundle.properties`.

## Source Structure

```
src/main/kotlin/com/jonnyzzz/intellij/hotreload/
├── HotReloadBundle.kt           # Message bundle accessor
├── HotReloadHttpHandler.kt      # HTTP REST endpoint with streaming response
├── HotReloadMarkerService.kt    # Marker file management (Disposable)
├── HotReloadNotifications.kt    # IDE balloon notifications
├── HotReloadStartupActivity.kt  # Startup trigger for marker service
└── PluginHotReloadService.kt    # Plugin reload logic, ID extraction, self-reload check

src/main/resources/
├── META-INF/plugin.xml                    # Plugin descriptor
├── messages/HotReloadBundle.properties    # Localized messages
└── hot-reload/README.md                   # Documentation (served via GET)

src/test/kotlin/com/jonnyzzz/intellij/hotreload/
├── MarkerFileTest.kt                      # Marker file creation/deletion tests
└── PluginHotReloadServiceTest.kt          # Plugin ID extraction and reload tests
```

## Key IntelliJ Platform APIs Used

### Plugin Loading/Unloading

```kotlin
// Check if plugin can be dynamically unloaded
DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)

// Unload a plugin
DynamicPlugins.unloadPlugin(descriptor, options)

// Install and load a plugin from zip file
PluginInstaller.installAndLoadDynamicPlugin(zipFile, parent, descriptor)

// Load plugin descriptor from zip/jar file
loadDescriptorFromArtifact(file, null)

// Find plugin by ID
PluginManagerCore.getPlugin(pluginId)

// Get plugin descriptor for a class
PluginManager.getPluginByClass(MyClass::class.java)
```

### HTTP Request Handler

The plugin uses `HttpRequestHandler` extension point with Netty:

```kotlin
class HotReloadHttpHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean
    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean
}
```

For streaming responses, use chunked transfer encoding:
```kotlin
val response = DefaultHttpResponse(HTTP_1_1, OK)
response.headers().set(TRANSFER_ENCODING, CHUNKED)
response.headers().set(CONTENT_TYPE, "text/plain; charset=utf-8")
channel.write(response)
// Then write chunks with DefaultHttpContent
// End with LastHttpContent.EMPTY_LAST_CONTENT
```

## Hot Reload Flow

1. **Receive POST request** with plugin zip file
2. **Extract plugin ID** from zip (checks both flat and nested jar structures)
3. **Check for self-reload** - reject if plugin ID matches this plugin
4. **Find existing plugin** by ID using `PluginManagerCore.getPlugin(pluginId)`
5. **Check if dynamic unload is possible** using `DynamicPlugins.checkCanUnloadWithoutRestart()`
6. **Unload existing plugin** using `DynamicPlugins.unloadPlugin()`
7. **Delete old plugin folder** (rename to `.old.<timestamp>` first, then delete)
8. **Load descriptor** from zip using `loadDescriptorFromArtifact()`
9. **Install and load** using `PluginInstaller.installAndLoadDynamicPlugin()`

## Testing

Tests use IntelliJ Platform test framework:

```kotlin
class PluginHotReloadServiceTest : BasePlatformTestCase() {
    fun testExtractPluginIdFromJarInsideZip() {
        // Test nested jar structure extraction
    }

    fun testSelfReloadIsBlocked() {
        // Test that self-reload is rejected
    }
}
```

### Key Test Cases

- `testExtractPluginIdFromValidZip` - flat structure
- `testExtractPluginIdFromNestedPluginXml` - nested META-INF
- `testExtractPluginIdFromJarInsideZip` - jar inside zip (production format)
- `testExtractPluginIdFromSecondJarInZip` - multiple jars
- `testExtractPluginIdLikeMcpSteroids` - realistic plugin structure
- `testSelfReloadIsBlocked` - self-reload prevention (flat)
- `testSelfReloadFromJarIsBlocked` - self-reload prevention (jar)

## plugin.xml Extensions

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- HTTP REST API endpoint -->
    <httpRequestHandler
        implementation="com.jonnyzzz.intellij.hotreload.HotReloadHttpHandler"/>

    <!-- Startup activity to create marker file -->
    <postStartupActivity
        implementation="com.jonnyzzz.intellij.hotreload.HotReloadStartupActivity"/>

    <!-- Notification group for progress balloons -->
    <notificationGroup
        id="Plugin Hot Reload"
        displayType="BALLOON"/>
</extensions>
```

## Message Bundle

`HotReloadBundle.properties` contains all user-facing messages:

```properties
# Progress messages
progress.starting=Starting plugin hot reload, zip size: {0} bytes
progress.extracting.id=Extracting plugin ID from zip...
progress.plugin.id=Plugin ID: {0}
...

# Error messages
error.no.plugin.id=Could not determine plugin ID from zip
error.self.reload=Cannot hot-reload the hot-reload plugin itself. Please restart the IDE to update this plugin.
error.descriptor.load.failed=Failed to load plugin descriptor
...
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

## Common Issues

### "Could not determine plugin ID from zip"

The plugin couldn't find `META-INF/plugin.xml` in either:
- Top-level: `plugin-name/META-INF/plugin.xml`
- Inside jars: `plugin-name/lib/*.jar` containing `META-INF/plugin.xml`

Check that your plugin zip has the correct structure.

### "Cannot hot-reload the hot-reload plugin itself"

This is expected - the hot-reload plugin cannot reload itself because the reload code would be unloaded mid-execution. Restart the IDE to update the hot-reload plugin.

### Streaming not working with curl

Use `curl -N` (no-buffer) to see real-time streaming output:
```bash
curl -N -X POST -H "Authorization: $TOKEN" --data-binary @plugin.zip http://localhost:63342/api/plugin-hot-reload
```
