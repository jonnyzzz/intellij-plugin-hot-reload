package com.jonnyzzz.intellij.hotreload

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.script.IdeScriptEngineManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Startup activity that ensures the marker service is initialized.
 * The actual marker file creation is handled by [HotReloadMarkerService].
 */
class HotReloadStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Trigger service initialization which creates the marker file
        service<HotReloadMarkerService>().createFiles()

        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(DynamicPluginListener.TOPIC, object : DynamicPluginListener {
                override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
                    dropCache()
                }

                override fun pluginUnloaded(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
                }

                override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
                    dropCache()
                }
            })
    }
}

/**
 * workaround
 * https://youtrack.jetbrains.com/issue/IJPL-225253/IdeScriptEngineManagerImpl.AllPluginsLoader-leak-classloader
 */
fun dropCache() {
    val inst = IdeScriptEngineManager.getInstance()
    val cl = inst.javaClass.classLoader
    val clazz = cl.loadClass($$"com.intellij.ide.script.IdeScriptEngineManagerImpl$AllPluginsLoader")

    val inzField = clazz.getDeclaredField("INSTANCE")
    inzField.isAccessible = true
    val inz = inzField.get(null)

    val gzzField = clazz.getDeclaredField("myLuckyGuess")
    gzzField.isAccessible = true
    val map = gzzField.get(inz) as ConcurrentHashMap<*,*>
    map.clear()
}
