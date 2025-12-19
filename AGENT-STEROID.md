# AGENT-STEROID.md - IntelliJ API Usage Guide for LLM Agents

This document provides instructions for LLM agents (like Claude) on how to effectively use the MCP Steroid server to interact with the IntelliJ Platform API. The goal is to make you a **power user of IntelliJ APIs** - always prefer using IntelliJ's capabilities over manual file operations.

## Core Philosophy

**BE AGGRESSIVE WITH INTELLIJ API USAGE.**

Instead of:
- Reading files manually with file tools → Use IntelliJ's VFS and PSI
- Searching with grep → Use IntelliJ's Find Usages, Structural Search
- Manual refactoring → Use IntelliJ's automated refactorings
- Guessing code structure → Query the project model directly

The IDE has indexed everything. It knows the code better than any file search. **USE IT.**

## Available MCP Tools

### `steroid_list_projects`
List all open projects in the IDE. Use this to get project names for `steroid_execute_code`.

### `steroid_execute_code`
Execute Kotlin code directly in IntelliJ's runtime. This is your primary tool.

Parameters:
- `project_name` (required): Target project from `steroid_list_projects`
- `code` (required): Kotlin code with `execute { }` block
- `reason` (required): Human-readable explanation of what you're doing
- `task_id` (required): Group related executions together
- `timeout` (optional): Execution timeout in seconds (default: 60)

### `steroid_execute_feedback`
Provide feedback on execution results. Use after `steroid_execute_code` to rate success.

## Critical Rules

### 1. The `execute { }` Block is a SUSPEND Function

The code inside `execute { }` runs in a **coroutine context**. This means:
- **Prefer Kotlin coroutine APIs** over blocking Java APIs
- You can call any `suspend` function directly
- **NEVER use `runBlocking`** - it will cause deadlocks

### 2. IMPORTS MUST Be OUTSIDE `execute { }`

**WRONG:**
```kotlin
execute {
    import com.intellij.psi.PsiManager  // ERROR: imports don't work here!
    // ...
}
```

**CORRECT:**
```kotlin
import com.intellij.psi.PsiManager
import com.intellij.openapi.application.readAction

execute {
    val psiFile = readAction {
        PsiManager.getInstance(project).findFile(virtualFile)
    }
}
```

### 3. Read/Write Actions for PSI/VFS

IntelliJ requires proper threading:
- **`readAction { }`** - for reading PSI, VFS, indices
- **`writeAction { }`** - for modifying PSI, VFS, documents

Both are suspend functions that work naturally in the execute block.

## Script Structure Template

```kotlin
// 1. IMPORTS - Always at the top, outside execute
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.psi.PsiManager

// 2. EXECUTE BLOCK - Your suspend function
execute {
    // 3. Wait for indexing if needed
    waitForSmartMode()

    // 4. Use IntelliJ APIs with proper read/write actions
    val result = readAction {
        // PSI/VFS reads here
    }

    // 5. Output results
    println(result)
}
```

## Context Available in `execute { }`

```kotlin
execute {
    // Available properties:
    project      // The IntelliJ Project instance
    params       // Original tool parameters (JsonElement)
    disposable   // For resource cleanup
    isDisposed   // Check if context is disposed

    // Output methods:
    println("Values", "separated", "by spaces")
    // Pretty-print as JSON
    printJson(object {
       val foo = 42
       val bar = "cool"
    })
    printJson(anyOtherObject)
    progress("Working on step 1...")  // Throttled to 1/sec

    // IDE utilities (suspend functions):
    waitForSmartMode()  // Wait for indexing before PSI operations
}
```

## Common Patterns

### 1. Get Project Information

```kotlin
execute {
    println("Project: ${project.name}")
    println("Base path: ${project.basePath}")
    println("Is open: ${project.isOpen}")
}
```

### 2. Access System/IDE Information

```kotlin
execute {
    // Java version
    println("Java: ${System.getProperty("java.version")}")

    // IDE log path
    val logPath = com.intellij.openapi.application.PathManager.getLogPath()
    println("Log: $logPath/idea.log")

    // Plugin info
    val plugins = com.intellij.ide.plugins.PluginManagerCore.getLoadedPlugins()
    plugins.filter { it.isEnabled }.take(10).forEach {
        println("  ${it.name}: ${it.version}")
    }
}
```

### 3. Find and Inspect Plugins

```kotlin
execute {
    val plugin = com.intellij.ide.plugins.PluginManagerCore.loadedPlugins
        .find { it.pluginId.idString == "com.example.myplugin" }

    if (plugin != null) {
        println("Found: ${plugin.name}")
        println("Version: ${plugin.version}")
        println("Enabled: ${plugin.isEnabled}")
        plugin.dependencies.forEach { dep ->
            println("  Depends on: ${dep.pluginId} (optional: ${dep.isOptional})")
        }
    }
}
```

### 4. Query Extension Points

```kotlin
execute {
    // List all extension points containing "kotlin" or "script"
    project.extensionArea.extensionPoints
        .filter { it.name.contains("kotlin", ignoreCase = true) }
        .forEach { ep ->
            println("${ep.name}: ${ep.extensionList.size} extensions")
        }
}
```

### 5. Open Another Project

```kotlin
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

execute {
    val projectPath = Path.of("/path/to/project")
    val projectManager = ProjectManagerEx.getInstanceEx()
    val result = projectManager.openProjectAsync(projectPath, OpenProjectTask { })
    println("Open result: $result")
}
```

### 6. Restart the IDE

```kotlin
execute {
    com.intellij.openapi.application.ApplicationManager.getApplication().restart()
}
```

### 7. Inspect JAR Contents

```kotlin
import java.util.jar.JarFile
import java.io.File

execute {
    val jarFile = JarFile(File("/path/to/plugin.jar"))
    jarFile.entries().toList()
        .filter { it.name.endsWith(".class") }
        .forEach { println(it.name) }
    jarFile.close()
}
```

### 8. Use Reflection for Exploration

```kotlin
execute {
    try {
        val clazz = Class.forName("org.jetbrains.kotlin.idea.SomeClass")
        println("Found class: ${clazz.name}")

        clazz.methods.filter { it.parameterCount == 0 }.take(10).forEach { m ->
            println("  ${m.name}(): ${m.returnType.simpleName}")
        }
    } catch (e: ClassNotFoundException) {
        println("Class not found")
    }
}
```

## Power User Patterns

### Find Usages via PSI

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.openapi.application.readAction

execute {
    waitForSmartMode()

    readAction {
        val psiElement = // ... get your element
        val usages = ReferencesSearch.search(psiElement, GlobalSearchScope.projectScope(project))
        usages.forEach { ref ->
            println("Usage at: ${ref.element.containingFile?.virtualFile?.path}:${ref.element.textOffset}")
        }
    }
}
```

### Navigate Project Structure

```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

execute {
    val contentRoots = ProjectRootManager.getInstance(project).contentRoots
    contentRoots.forEach { root ->
        println("Content root: ${root.path}")
        VfsUtil.iterateChildrenRecursively(root, null) { file ->
            if (file.extension == "kt") {
                println("  Kotlin file: ${file.path}")
            }
            true
        }
    }
}
```

### Run Inspections Programmatically

```kotlin
import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.application.readAction

execute {
    waitForSmartMode()

    readAction {
        val inspectionManager = InspectionManager.getInstance(project)
        // ... run specific inspections
    }
}
```

### Execute Actions

```kotlin
import com.intellij.openapi.actionSystem.ActionManager

execute {
    val action = ActionManager.getInstance().getAction("GotoFile")
    if (action != null) {
        println("Found action: ${action.templatePresentation.text}")
    }
}
```

## Error Handling

Always handle errors gracefully:

```kotlin
execute {
    try {
        // risky operation
    } catch (e: Exception) {
        println("Error: ${e.javaClass.simpleName} - ${e.message}")
        e.printStackTrace() // goes to IDE log
    }
}
```

## Best Practices

1. **Always call `waitForSmartMode()` before PSI operations** - during indexing, many APIs return incomplete data

2. **Use `readAction { }` for any PSI/VFS read** - even simple property access

3. **Use `writeAction { }` for any PSI/VFS modification** - always

4. **Keep imports outside `execute { }`** - imports inside won't work

5. **Leverage suspend context** - prefer Kotlin coroutine APIs (`delay`, `async`, `withContext`)

6. **Use meaningful `task_id`** - groups related executions for tracking

7. **Report progress for long operations** - `progress("Step 1 of 5...")`

8. **Print results** - the output is your only way to see what happened

9. **Use reflection cautiously** - APIs may change between IDE versions

10. **Prefer IntelliJ APIs over file operations** - the IDE has already indexed everything

## Debugging Tips

1. **Check IDE logs**: Use `steroid_execute_code` to get the log path:
   ```kotlin
   execute {
       println(com.intellij.openapi.application.PathManager.getLogPath() + "/idea.log")
   }
   ```

2. **Print class info**: When unsure about an object:
   ```kotlin
   println("Type: ${obj?.javaClass?.name}")
   println("Methods: ${obj?.javaClass?.methods?.map { it.name }}")
   ```

3. **Use printJson**: For complex objects, JSON serialization often works:
   ```kotlin
   printJson(mapOf("key" to value, "count" to items.size))
   ```

## Remember

The MCP Steroid server gives you **direct access to IntelliJ's runtime**. This is incredibly powerful:

- You can query the project model
- You can invoke any IntelliJ API
- You can run refactorings
- You can execute actions
- You can inspect plugins
- You can access PSI (the parsed code model)

**The execute block is a suspend function.** Use this to your advantage - call suspend APIs directly, use coroutine primitives, and never block.

**Don't settle for file-level operations when you have IDE-level access.**

Be bold. Explore the API. Use reflection to discover. The IDE is your tool - wield it.
