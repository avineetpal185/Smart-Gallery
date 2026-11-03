package com.smartgallery.app

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * True when the user has granted "Notification access" to Smart Gallery.
 */
/** Small status notes shown in the app so problems can be seen without Logcat. */
object Diag {

    private const val PREFS = "diag"

    fun set(context: Context, key: String, value: String) {
        val time = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, "$time  $value")
            .apply()
    }

    fun get(context: Context, key: String): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, "none yet") ?: "none yet"
    }
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    return NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)
}

/**
 * Persistent log of "WhatsApp photo notification arrived from chat X at time T".
 *
 * The detector uses it to decide which group a new image belongs to.
 * It stores events for ALL chats (selected or not, group or direct), because
 * ambiguity detection needs to see everything that happened near an image.
 */
object GroupEventStore {

    private const val PREFS = "group_events"
    private const val KEY = "events_v1"
    private const val MAX_AGE_MS = 30 * 60 * 1000L
    private const val MAX_EVENTS = 300

    data class ChatRef(val name: String, val isGroup: Boolean)

    private data class Event(
        val postTime: Long,
        val msgTime: Long,
        val isGroup: Boolean,
        val name: String
    )

    /** Returns false if this exact message was already recorded. */
    @Synchronized
    fun record(
        context: Context,
        name: String,
        isGroup: Boolean,
        postTime: Long,
        msgTime: Long
    ): Boolean {
        val clean = clean(name)
        val events = load(context).toMutableList()

        val duplicate = events.any {
            it.msgTime == msgTime &&
                    it.isGroup == isGroup &&
                    it.name.equals(clean, ignoreCase = true)
        }
        if (duplicate) return false

        events.add(Event(postTime, msgTime, isGroup, clean))
        save(context, events.takeLast(MAX_EVENTS))
        return true
    }

    /** Distinct chats that posted a photo notification inside [from, to]. */
    @Synchronized
    fun chatsBetween(context: Context, from: Long, to: Long): List<ChatRef> {
        return load(context)
            .filter { it.postTime in from..to }
            .map { ChatRef(it.name, it.isGroup) }
            .distinctBy { it.name.lowercase() to it.isGroup }
    }

    private fun clean(name: String): String =
        name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun load(context: Context): List<Event> {
        val raw = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""

        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val result = mutableListOf<Event>()

        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val p = line.split('\t', limit = 4)
            if (p.size < 4) continue
            val post = p[0].toLongOrNull() ?: continue
            val msg = p[1].toLongOrNull() ?: continue
            if (post < cutoff) continue
            result.add(Event(post, msg, p[2] == "1", p[3]))
        }
        return result
    }

    private fun save(context: Context, events: List<Event>) {
        val text = events.joinToString("\n") {
            "${it.postTime}\t${it.msgTime}\t${if (it.isGroup) "1" else "0"}\t${it.name}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, text)
            .apply()
    }
}

/**
 * Reads WhatsApp notifications (user must grant Notification access).
 *
 * Two jobs:
 *  1. DISCOVERY: every WhatsApp group seen is added to the group list.
 *  2. ATTRIBUTION: every photo notification is recorded with its chat name,
 *     so the detector can tell which chat a new image file came from.
 *
 * It never reads message text for any other purpose.
 */
class GroupNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "GroupListener"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val SETTINGS_PREFS = "smart_gallery_settings"

        // Must match LOCAL_GROUPS_KEY in MainActivity.kt
        private const val LOCAL_GROUPS_KEY = "local_group_names"

        private val PHOTO_ONLY = Regex(
            "^(?:[^:]{0,60}:\\s*)?(?:\\p{So}\\s*)?(?:photo|image|\\d+\\s+photos|\\d+\\s+images)(?:\\s*\\(\\d+\\))?$",
            RegexOption.IGNORE_CASE
        )

        private val COUNT_SUFFIX =
            Regex("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*$", RegexOption.IGNORE_CASE)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "LISTENER CONNECTED")
        Diag.set(applicationContext, "listener", "CONNECTED")
        // Discover groups from notifications that are already showing.
        try {
            activeNotifications?.forEach { handle(it, discoverOnly = true) }
        } catch (e: Exception) {
            Log.e(TAG, "Initial scan failed", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Diag.set(applicationContext, "listener", "DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn, discoverOnly = false)
        } catch (e: Exception) {
            Log.e(TAG, "Notification handling failed", e)
        }
    }

    private fun handle(sbn: StatusBarNotification, discoverOnly: Boolean) {

        if (sbn.packageName != WHATSAPP_PACKAGE) return

        Log.d(TAG, "WhatsApp notification received")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val n = sbn.notification ?: return

        // Skip the "3 new messages from 2 chats" summary notification.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras ?: return

        val convTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title =
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val flaggedGroup =
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        val rawName =
            (convTitle?.takeIf { it.isNotBlank() } ?: title) ?: return

        val name = rawName.replace(COUNT_SUFFIX, "").trim()

        if (name.isBlank() || name.equals("WhatsApp", ignoreCase = true)) return

        // Newest message in this notification.
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = messages?.lastOrNull() as? Bundle

        val lastText =
            last?.getCharSequence("text")?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        val msgTime =
            last?.getLong("time", 0L)?.takeIf { it > 0L } ?: n.`when`

        // A chat is a group if WhatsApp says so, or if the name is already
        // one the user knows (added manually or discovered earlier).
        val isGroup = flaggedGroup || isKnownGroup(name)

        Log.d(
            TAG,
            "name=$name flaggedGroup=$flaggedGroup isGroup=$isGroup text=$lastText"
        )

        Diag.set(
            applicationContext,
            "notif",
            "chat=$name group=$isGroup text=${lastText?.take(40)}"
        )

        if (flaggedGroup) {
            rememberGroup(name)
        }

        if (discoverOnly) return

        // WhatsApp shows photos as "📷 Photo". Require the camera emoji so a
        // plain text message is never mistaken for an image.
        if (looksLikePhoto(lastText)) {

            val recorded = GroupEventStore.record(
                context = applicationContext,
                name = name,
                isGroup = isGroup,
                postTime = sbn.postTime,
                msgTime = msgTime
            )

            if (recorded) {
                Log.d(TAG, "PHOTO EVENT recorded: chat=$name group=$isGroup")
                Diag.set(applicationContext, "photo", "chat=$name group=$isGroup")
                // Make sure the image gets matched soon after the window closes.
                WhatsAppMediaWorker.scheduleKick(applicationContext)
            }
        }
    }

    /**
     * True for notification text such as:
     *   "📷 Photo", "📷 caption text", "🖼 Photo", "Photo",
     *   "Sender: Photo", "Photo (2)", "3 photos"
     * A normal text message that merely contains the word "photo" is NOT matched.
     */
    private fun looksLikePhoto(text: String?): Boolean {

        if (text.isNullOrBlank()) return false

        if (text.contains("📷") || text.contains("🖼")) return true

        return PHOTO_ONLY.matches(text.trim())
    }

    private fun isKnownGroup(name: String): Boolean {
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet(LOCAL_GROUPS_KEY, emptySet()) ?: emptySet()
        return known.any { it.equals(name, ignoreCase = true) }
    }

    private fun rememberGroup(name: String) {
        synchronized(GroupEventStore) {
            val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            val set = (prefs.getStringSet(LOCAL_GROUPS_KEY, emptySet()) ?: emptySet())
                .toMutableSet()

            if (set.none { it.equals(name, ignoreCase = true) }) {
                set.add(name)
                prefs.edit().putStringSet(LOCAL_GROUPS_KEY, set).apply()
                Log.d(TAG, "NEW GROUP discovered: $name")
            }
        }
    }
}