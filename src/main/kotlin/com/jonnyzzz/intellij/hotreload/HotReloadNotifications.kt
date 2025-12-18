package com.jonnyzzz.intellij.hotreload

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Wrapper for plugin identification in notifications.
 */
sealed class PluginInfo {
    data class Known(val id: String, val name: String) : PluginInfo()
    data object Unknown : PluginInfo()
}

fun PluginHotReloadService.ReloadResult.toPluginInfo(): PluginInfo =
    if (pluginId != null && pluginName != null) PluginInfo.Known(pluginId, pluginName)
    else PluginInfo.Unknown

/**
 * Service for showing IDE notifications during plugin hot reload.
 */
@Service(Service.Level.APP)
class HotReloadNotificationService {
    companion object {
        fun getInstance(): HotReloadNotificationService = service()
    }

    fun showSuccess(plugin: PluginInfo) {
        ApplicationManager.getApplication().invokeLater {
            val title = HotReloadBundle.message("notification.success.title")
            val content = when (plugin) {
                is PluginInfo.Known -> HotReloadBundle.message("notification.success.content.known", plugin.name)
                is PluginInfo.Unknown -> HotReloadBundle.message("notification.success.content.unknown")
            }
            createNotification(title, content, NotificationType.INFORMATION)
        }
    }

    fun showError(plugin: PluginInfo, message: String) {
        ApplicationManager.getApplication().invokeLater {
            val (title, content) = when (plugin) {
                is PluginInfo.Known -> HotReloadBundle.message("notification.error.title.known") to
                        HotReloadBundle.message("notification.error.content.known", plugin.name, message)
                is PluginInfo.Unknown -> HotReloadBundle.message("notification.error.title.unknown") to message
            }
            createNotification(title, content, NotificationType.ERROR)
        }
    }

    private fun createNotification(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(HotReloadBundle.message("notification.group.name"))
            .createNotification(title, content, type)
            .notify(null)
    }
}
