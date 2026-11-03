package com.smartgallery.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val SETTINGS_PREFS = "smart_gallery_settings"
private const val PENDING_KEY = "pending_group_import"
private const val GROUPS_KEY = "local_group_names"

/** True when the user turned on the "Smart Gallery group import" accessibility service. */
fun isImportServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val expected = "${context.packageName}/${GroupImportAccessibilityService::class.java.name}"

    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

/** Group names read from WhatsApp that the user has not confirmed yet. */
fun readPendingImport(context: Context): List<String> {
    val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    return (prefs.getStringSet(PENDING_KEY, emptySet()) ?: emptySet()).sorted()
}

fun clearPendingImport(context: Context) {
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit().remove(PENDING_KEY).apply()
}

/**
 * Reads the visible chat-row titles while WhatsApp is open and the user scrolls
 * the chat list. Names are only STAGED here. Nothing is added to the group list
 * until the user confirms in Smart Gallery.
 *
 * It reads only the chat-title text. It never clicks, types, or reads messages.
 */
class GroupImportAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GroupImport"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        // WhatsApp's id for the chat-row title. If WhatsApp renames it in an
        // update, the Logcat output below shows the ids it really uses.
        private const val TITLE_ID = "com.whatsapp:id/conversations_row_contact_name"
    }

    private var loggedIds = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return
        if (event.packageName?.toString() != WHATSAPP_PACKAGE) return

        val root = rootInActiveWindow ?: return

        try {

            val nodes = root.findAccessibilityNodeInfosByViewId(TITLE_ID)

            val names = nodes
                .mapNotNull { it.text?.toString()?.trim() }
                .filter { it.isNotBlank() && !it.endsWith("…") && !it.endsWith("...") }

            if (names.isNotEmpty()) {
                stage(names)
            } else if (!loggedIds) {
                loggedIds = true
                val ids = linkedSetOf<String>()
                collectIds(root, ids, 0)
                Log.d(TAG, "Title id not found. Ids seen: $ids")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Read failed", e)
        }
    }

    private fun collectIds(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 25 || out.size >= 30) return
        node.viewIdResourceName?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectIds(node.getChild(i), out, depth + 1)
        }
    }

    private fun stage(names: List<String>) {

        synchronized(GroupEventStore) {

            val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

            val known = prefs.getStringSet(GROUPS_KEY, emptySet()) ?: emptySet()

            val pending = (prefs.getStringSet(PENDING_KEY, emptySet()) ?: emptySet())
                .toMutableSet()

            var changed = false

            for (name in names) {
                val already =
                    known.any { it.equals(name, ignoreCase = true) } ||
                            pending.any { it.equals(name, ignoreCase = true) }

                if (!already) {
                    pending.add(name)
                    changed = true
                }
            }

            if (changed) {
                prefs.edit().putStringSet(PENDING_KEY, pending).apply()
                Log.d(TAG, "Staged ${pending.size} names for import")
            }
        }
    }

    override fun onInterrupt() {}
}