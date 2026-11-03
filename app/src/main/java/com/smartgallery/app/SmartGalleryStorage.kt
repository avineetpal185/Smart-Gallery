package com.smartgallery.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * METADATA ONLY. No image is ever copied, moved or created.
 *
 * For every ORIGINAL WhatsApp image that Smart Gallery manages we keep ONE record:
 *     MediaStore id | expiry time | active-or-pending | display name | group
 *
 *  - One record per MediaStore id: registering the same image again does nothing,
 *    so the expiry timer is never reset.
 *  - markExpiredAsPending() flips expired records to "pending deletion".
 *    It never deletes anything.
 *  - The real deletion is done by ExpiredImageDeleter, from the open app, after
 *    Android's own confirmation screen.
 *  - A record is removed only when the original is confirmed gone from MediaStore.
 */
object SmartGalleryStorage {

    private const val TAG = "SGStorage"

    private const val PREFS_NAME = "smart_gallery_originals_v1"
    private const val KEY_TRACKED = "tracked"

    private const val SETTINGS_PREFS = "smart_gallery_settings"
    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    private const val QUERY_CHUNK = 400

    private val WA_NAME = Regex("^IMG-\\d{8}-WA\\d+\\.\\w+$", RegexOption.IGNORE_CASE)

    data class Tracked(
        val id: Long,
        val expiresAt: Long,
        val pending: Boolean,
        val name: String,
        val group: String
    ) {
        val uri: Uri
            get() = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            )
    }

    // ============================================================
    // REGISTER AN ORIGINAL IMAGE (metadata only)
    // Returns true if newly registered, false if it was already tracked.
    // An existing record is NEVER changed, so the timer is never reset.
    // ============================================================
    @Synchronized
    fun registerOriginal(
        context: Context,
        mediaId: Long,
        displayName: String,
        groupName: String,
        expiresAt: Long
    ): Boolean {

        val preferences = prefs(context)
        val map = load(preferences)

        if (map.containsKey(mediaId)) {
            Log.d(TAG, "Already tracked, timer kept: id=$mediaId")
            return false
        }

        map[mediaId] = Tracked(
            id = mediaId,
            expiresAt = expiresAt,
            pending = false,
            name = clean(displayName),
            group = clean(groupName)
        )

        save(preferences, map)

        Log.d(TAG, "TRACKED original: id=$mediaId group=$groupName expiresAt=$expiresAt")
        return true
    }

    /** Snapshot of every tracked image (for the gallery screen). */
    @Synchronized
    fun trackedList(context: Context): List<Tracked> {
        return load(prefs(context)).values.toList()
    }

    // ============================================================
    // EXPIRED -> PENDING DELETION (never deletes)
    // Returns how many images are waiting for the user's approval.
    // Only groups that are CURRENTLY selected are ever marked.
    // ============================================================
    @Synchronized
    fun markExpiredAsPending(context: Context): Int {

        val preferences = prefs(context)
        val map = load(preferences)

        if (map.isEmpty()) return 0

        dropMissing(context, map)

        val selected = selectedGroups(context)
        val now = System.currentTimeMillis()

        for (item in map.values.toList()) {

            if (!item.pending &&
                item.expiresAt <= now &&
                norm(item.group) in selected
            ) {
                map[item.id] = item.copy(pending = true)
                Log.d(TAG, "PENDING DELETION: id=${item.id} group=${item.group}")
            }
        }

        save(preferences, map)

        return map.values.count { it.pending && norm(it.group) in selected }
    }

    /** Number of images waiting for approval (cheap, no MediaStore query). */
    @Synchronized
    fun pendingCount(context: Context): Int {
        val selected = selectedGroups(context)
        return load(prefs(context)).values.count {
            it.pending && norm(it.group) in selected
        }
    }

    // ============================================================
    // URIs TO ASK ANDROID TO DELETE
    // Only: expired + pending + selected group + still the same WhatsApp image.
    // ============================================================
    @Synchronized
    fun pendingUris(context: Context): List<Uri> {

        val preferences = prefs(context)
        val map = load(preferences)

        dropMissing(context, map)
        save(preferences, map)

        val selected = selectedGroups(context)
        val now = System.currentTimeMillis()

        return map.values
            .filter { it.pending && it.expiresAt <= now && norm(it.group) in selected }
            .filter { isSameWhatsAppImage(context, it) }
            .map { it.uri }
    }

    // ============================================================
    // AFTER Android's deletion screen: forget everything that is really gone.
    // Records of images that still exist stay pending.
    // Returns how many pending records remain.
    // ============================================================
    @Synchronized
    fun reconcile(context: Context): Int {

        val preferences = prefs(context)
        val map = load(preferences)

        dropMissing(context, map)
        save(preferences, map)

        return map.values.count { it.pending }
    }

    // ============================================================
    // AUTOMATIC DELETE (no confirmation screen)
    //
    // Works ONLY on Android 12+ AND only after the user turned on
    // "Media management" for Smart Gallery in Android Settings (one time).
    // Otherwise it does nothing and returns 0 - the normal approval flow is used.
    // Returns how many originals were deleted.
    // ============================================================
    @Synchronized
    fun deletePendingSilently(context: Context): Int {

        if (!canDeleteWithoutPrompt(context)) return 0

        var deleted = 0

        for (uri in pendingUris(context)) {

            var ok = false

            // 1) Normal MediaStore delete (allowed once "All files access" is on).
            try {
                ok = context.contentResolver.delete(uri, null, null) > 0
            } catch (exception: Exception) {
                Log.e(TAG, "MediaStore delete failed for $uri: ${exception.message}")
            }

            // 2) Fallback: delete the file itself, then refresh MediaStore.
            if (!ok && hasAllFilesAccess()) {
                ok = deleteFileOf(context, uri)
            }

            if (ok) {
                deleted++
                Log.d(TAG, "AUTO-DELETED original: $uri")
            } else {
                // Stays pending; the approval screen is offered as a fallback.
                Log.e(TAG, "Auto delete failed for $uri")
            }
        }

        reconcile(context)

        return deleted
    }

    /**
     * True when Android lets this app delete the WhatsApp originals with no
     * confirmation screen: the user turned on "All files access" (Android 11+)
     * or "Media management" (Android 12+) once in Settings.
     */
    fun canDeleteWithoutPrompt(context: Context): Boolean {

        if (hasAllFilesAccess()) return true

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                MediaStore.canManageMedia(context)
    }

    fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                android.os.Environment.isExternalStorageManager()
    }

    @Suppress("DEPRECATION")
    private fun deleteFileOf(context: Context, uri: Uri): Boolean {

        return try {

            var path: String? = null

            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) path = cursor.getString(0)
            }

            val filePath = path ?: return false

            val file = java.io.File(filePath)

            val removed = !file.exists() || file.delete()

            if (removed) {

                // Remove the leftover MediaStore row / refresh the gallery.
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }

                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(filePath),
                    null,
                    null
                )
            }

            removed

        } catch (exception: Exception) {
            Log.e(TAG, "File delete failed for $uri: ${exception.message}")
            false
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun clean(text: String): String =
        text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun norm(text: String): String = text.trim().lowercase()

    private fun selectedGroups(context: Context): Set<String> {

        val settings =
            context.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

        val result = mutableSetOf<String>()

        for (entry in settings.all) {
            if (entry.key.startsWith("selected_") && entry.value == true) {
                result.add(norm(entry.key.removePrefix("selected_")))
            }
        }

        return result
    }

    private fun load(preferences: SharedPreferences): MutableMap<Long, Tracked> {

        val map = LinkedHashMap<Long, Tracked>()

        val raw = preferences.getStringSet(KEY_TRACKED, emptySet()) ?: emptySet()

        for (line in raw) {

            val p = line.split('\t', limit = 5)

            if (p.size < 5) continue

            val id = p[0].toLongOrNull() ?: continue
            val expiresAt = p[1].toLongOrNull() ?: continue

            val existing = map[id]

            // Same id twice (should not happen): keep the EARLIER expiry.
            if (existing != null && existing.expiresAt <= expiresAt) continue

            map[id] = Tracked(
                id = id,
                expiresAt = expiresAt,
                pending = p[2] == "P",
                name = p[3],
                group = p[4]
            )
        }

        return map
    }

    private fun save(preferences: SharedPreferences, map: Map<Long, Tracked>) {

        val set = map.values.map {
            "${it.id}\t${it.expiresAt}\t${if (it.pending) "P" else "A"}\t${it.name}\t${it.group}"
        }.toSet()

        // commit() so a record is never lost if the process is killed.
        preferences.edit().putStringSet(KEY_TRACKED, set).commit()
    }

    private fun hasMediaPermission(context: Context): Boolean {

        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Which of these ids still exist in MediaStore?
     * Returns null when we cannot know (no permission / query error).
     * Without permission MediaStore hides other apps' images, which must NEVER
     * be mistaken for "already deleted".
     */
    private fun existingIds(context: Context, ids: Collection<Long>): Set<Long>? {

        if (!hasMediaPermission(context)) return null

        val found = HashSet<Long>()

        try {

            for (chunk in ids.chunked(QUERY_CHUNK)) {

                val selection =
                    "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})"

                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    selection,
                    null,
                    null
                ) ?: return null

                cursor.use {
                    while (it.moveToNext()) {
                        found.add(it.getLong(0))
                    }
                }
            }

        } catch (exception: Exception) {
            Log.e(TAG, "Existence check failed: ${exception.message}")
            return null
        }

        return found
    }

    /** Removes records whose original is confirmed to be gone. */
    private fun dropMissing(context: Context, map: MutableMap<Long, Tracked>) {

        if (map.isEmpty()) return

        val existing = existingIds(context, map.keys.toList()) ?: return

        for (id in map.keys.toList()) {
            if (id !in existing) {
                map.remove(id)
                Log.d(TAG, "Original no longer exists, record removed: id=$id")
            }
        }
    }

    /**
     * Last safety check before asking Android to delete:
     * the id must still point at the SAME WhatsApp image (same file name),
     * and it must not be an image the user sent.
     */
    private fun isSameWhatsAppImage(context: Context, item: Tracked): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {

            context.contentResolver.query(
                item.uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH
                ),
                null,
                null,
                null
            )?.use { cursor ->

                if (!cursor.moveToFirst()) return@use false

                val name = cursor.getString(0) ?: ""
                val owner = cursor.getString(1)
                val path = (cursor.getString(2) ?: "").lowercase()

                if (clean(name) != item.name) {
                    Log.e(TAG, "Name changed, NOT deleting id=${item.id}")
                    return@use false
                }

                if (path.contains("/sent")) {
                    return@use false
                }

                owner.equals(WHATSAPP_PACKAGE, ignoreCase = true) ||
                        path.contains("whatsapp") ||
                        WA_NAME.matches(name)

            } ?: false

        } catch (exception: Exception) {
            false
        }
    }
}