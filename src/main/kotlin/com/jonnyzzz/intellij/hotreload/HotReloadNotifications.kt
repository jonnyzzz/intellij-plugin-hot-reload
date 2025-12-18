package com.jonnyzzz.intellij.hotreload

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager

/**
 * Helper for showing IDE notifications during plugin hot reload.
 */
//TODO use @Service, not object.
object HotReloadNotifications {
    private const val GROUP_ID = "Plugin Hot Reload"

    /**
     * Shows a progress notification that can be updated or replaced.
     */
    fun showProgress(pluginName: String): ProgressNotification {
        return ProgressNotification(pluginName)
    }

    /**
     * Shows a success notification.
     */
    fun showSuccess(pluginName: String) {
        ApplicationManager.getApplication().invokeLater {
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
     * Shows an error notification.
     */
    fun showError(pluginName: String?, message: String) {
        ApplicationManager.getApplication().invokeLater {
            val title = if (pluginName != null) "Plugin Reload Failed" else "Hot Reload Failed"
            val content = if (pluginName != null) {
                "Plugin '$pluginName' reload failed: $message"
            } else {
                message
            }

            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, NotificationType.ERROR)
                .notify(null)
        }
    }

    /**
     * A notification that shows progress and can be expired/replaced.
     */
    //TODO: maintain the map <pluginID -> Notificaiton> in the service,
    //TODO: do not allow more than 1 notificatino per plugin>
    class ProgressNotification(private val pluginName: String) {
        private var notification: com.intellij.notification.Notification? = null

        init {
            ApplicationManager.getApplication().invokeLater {
                notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(GROUP_ID)
                    .createNotification(
                        "Reloading Plugin",
                        "Reloading plugin '$pluginName'...",
                        NotificationType.INFORMATION
                    )
                notification?.notify(null)
            }
        }

        /**
         * Update the notification with a new message.
         */
        fun update(message: String) {
            ApplicationManager.getApplication().invokeLater {
                notification?.expire()
                notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(GROUP_ID)
                    .createNotification(
                        "Reloading Plugin",
                        message,
                        NotificationType.INFORMATION
                    )
                notification?.notify(null)
            }
        }

        /**
         * Mark the reload as successful and expire the progress notification.
         */
        fun success() {
            ApplicationManager.getApplication().invokeLater {
                notification?.expire()
                showSuccess(pluginName)
            }
        }

        /**
         * Mark the reload as failed and expire the progress notification.
         */
        fun error(message: String) {
            ApplicationManager.getApplication().invokeLater {
                notification?.expire()
                showError(pluginName, message)
            }
        }

        /**
         * Just expire the notification without showing success/error.
         */
        fun expire() {
            ApplicationManager.getApplication().invokeLater {
                notification?.expire()
            }
        }
    }
}
