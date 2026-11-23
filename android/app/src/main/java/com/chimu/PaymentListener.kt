package com.chimu

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

/**
 * Reads the notifications of the apps the user turned on under More -> Auto-log payments (RFC-005).
 * The user grants "Notification access" in the system settings; if they take it away again the system
 * simply stops calling this service and the app says how to turn it back on.
 *
 * Thin on purpose: it picks the title and text out of a notification and hands the message to
 * [ChimuApp.logMessage], where the tested logic lives. The text is not kept anywhere here. Only the
 * package name of each app is remembered (so the user can choose from the apps that post), and the
 * text of an app the user did not turn on is never looked at.
 */
class PaymentListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val app = applicationContext as? ChimuApp ?: return
            val posted = sbn ?: return
            val pkg = posted.packageName ?: return
            if (pkg == packageName) return // never read our own notifications

            app.autoLog.recordSeen(pkg)
            if (!app.autoLog.isEnabled(pkg)) return

            val notification = posted.notification ?: return
            if (posted.isOngoing || (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            val extras = notification.extras ?: return
            val text = MessageText.join(
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_TEXT),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            )
            if (text.isEmpty()) return

            val message = IncomingMessage(Channel.NOTIFICATION, pkg, text, posted.postTime)
            app.appScope.launch { app.logMessage(message) }
        } catch (e: Exception) {
            // A notification that can't be read must never take the service down. Nothing was stored.
        }
    }
}
