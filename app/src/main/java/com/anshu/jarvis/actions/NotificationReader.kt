package com.anshu.jarvis.actions

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets Jarvis read out your recent notifications ("who messaged me?"). */
class NotificationReader : NotificationListenerService() {

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }

        synchronized(recent) {
            recent.addFirst("$app. $title: $text")
            while (recent.size > 10) recent.removeLast()
        }
    }

    companion object {
        @Volatile private var instance: NotificationReader? = null
        private val recent = ArrayDeque<String>()

        fun readOut(count: Int = 5): String {
            if (instance == null) return "Give Jarvis notification access first"
            val items = synchronized(recent) { recent.take(count) }
            if (items.isEmpty()) return "You have no new notifications"
            return "You have ${items.size} recent notifications. " + items.joinToString(". ")
        }

        fun clearAll(): String {
            val s = instance ?: return "Give Jarvis notification access first"
            s.cancelAllNotifications()
            synchronized(recent) { recent.clear() }
            return "Notifications cleared"
        }
    }
}
