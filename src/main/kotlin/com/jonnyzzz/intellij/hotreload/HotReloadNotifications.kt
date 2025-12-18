package com.jonnyzzz.intellij.hotreload

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for showing IDE notifications during plugin hot reload.
 */
@Service(Service.Level.APP)
class HotReloadNotificationService {
    private val activeNotifications = ConcurrentHashMap<String, Notification>()

    companion object {
        private const val GROUP_ID = "Plugin Hot Reload"

        fun getInstance(): HotReloadNotificationService = service()
    }

    /**
     * Shows a progress notification for the given plugin.
     * If a notification already exists for this plugin, it will be expired first.
     */
    fun showProgress(pluginId: String, pluginName: String) {
        ApplicationManager.getApplication().invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(
                    "Reloading Plugin",
                    "Reloading plugin '$pluginName'...",
                    NotificationType.INFORMATION
                )
            activeNotifications.put(pluginId, notification)?.expire()
            notification.notify(null)
        }
    }

    /**
     * Shows a success notification and expires any active progress notification.
     */
    fun showSuccess(pluginId: String, pluginName: String) {
        ApplicationManager.getApplication().invokeLater {
            expireNotification(pluginId)

            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(
                    "Plugin Reloaded",
                    "Plugin '$pluginName' reloaded successfully",
                    NotificationType.INFORMATION
                )
                .notify(null)
        }
    }

    /**
     * Shows an error notification and expires any active progress notification.
     */
    fun showError(pluginId: String?, pluginName: String?, message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (pluginId != null) {
                expireNotification(pluginId)
            }

            val title = if (pluginName != null) "Plugin Reload Failed" else "Hot Reload Failed"
            val content = if (pluginName != null) {
                "Plugin '$pluginName' failed: $message"
            } else {
                message
            }

            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, NotificationType.ERROR)
                .notify(null)
        }
    }

    private fun expireNotification(pluginId: String) {
        activeNotifications.remove(pluginId)?.expire()
    }
}
