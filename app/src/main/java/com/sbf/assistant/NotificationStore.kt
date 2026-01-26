package com.sbf.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

data class NotificationEntry(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

class NotificationStore : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getString("android.title").orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val entry = NotificationEntry(
            packageName = sbn.packageName,
            title = title,
            text = text,
            timestamp = sbn.postTime
        )
        addEntry(entry)
    }

    companion object {
        private const val TAG = "NotificationStore"
        private val entries = CopyOnWriteArrayList<NotificationEntry>()

        fun addEntry(entry: NotificationEntry) {
            entries.add(0, entry)
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(entries.size - 1)
            }
        }

        fun getRecent(limit: Int): List<NotificationEntry> {
            val max = limit.coerceIn(1, MAX_ENTRIES)
            return entries.take(max)
        }

        private const val MAX_ENTRIES = 50
    }
}
