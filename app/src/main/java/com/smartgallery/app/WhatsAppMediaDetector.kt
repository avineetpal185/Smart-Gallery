package com.smartgallery.app

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WhatsAppMediaDetector {

    private const val TAG = "WhatsAppDetector"

    private const val PREFS_NAME = "whatsapp_detector"

    // v5: new keys so a bad v4 baseline (possibly 0) is replaced by a correct one.
    // First run after update creates a fresh baseline and tracks NOTHING.
    private const val KEY_BASELINE_ID = "baseline_id_v5"
    private const val KEY_PROCESSED_IDS = "processed_ids_v5"
    private const val KEY_FAIL_COUNTS = "fail_counts_v5"

    private const val MAX_FAILS = 3

    // Keep the processed-ID list from growing forever.
    private const val MAX_TRACKED = 3000
    private const val KEEP_TRACKED = 1500

    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    /*
     * Matching window between a new image file and a WhatsApp photo
     * notification:  [imageTime - BEFORE, imageTime + AFTER]
     *
     * Exactly one chat inside the window -> that chat owns the image.
     * Zero or several chats           -> NOT assigned (never guessed).
     */
    const val MATCH_BEFORE_MS = 90_000L
    const val MATCH_AFTER_MS = 30_000L

    enum class DetectorOutcome {
        DONE,
        MORE,
        RETRY
    }

    private data class Candidate(
        val id: Long,
        val name: String,
        val mime: String,
        val owner: String?,
        val path: String?,
        val addedMs: Long,
        val size: Long
    )

    // Signatures ("name|size") of photos already tracked. WhatsApp can register
    // the same file twice in MediaStore under two different IDs.
    private const val KEY_SIGNATURES = "saved_signatures_v1"
    private const val MAX_SIGNATURES = 500

    // A photo with the same name+size or same content, tracked within this
    // time, is treated as the same photo.
    private const val DEDUPE_WINDOW_MS = 30 * 60 * 1000L

    /*
     * Only one run at a time. Previously two workers (watch + drain) could run
     * together, read the same processed list, and overwrite each other's state.
     */
    private val lock = Any()

    // ============================================================
    // MAIN DETECTOR
    // ============================================================

    fun checkForNewWhatsAppImages(
        context: Context
    ): DetectorOutcome {
        return synchronized(lock) {
            runDetector(context.applicationContext)
        }
    }

    private fun runDetector(
        context: Context
    ): DetectorOutcome {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "Smart Gallery requires Android 10+.")
            return DetectorOutcome.DONE
        }

        // Without permission MediaStore shows only our own images, which would
        // create a wrong baseline (0) and later flood old images in.
        if (!hasMediaPermission(context)) {
            Log.d(TAG, "Media permission not granted yet. Nothing done.")
            return DetectorOutcome.DONE
        }

        val resolver = context.contentResolver

        val preferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ========================================================
        // FIRST RUN: baseline = newest image ID of ANY image.
        // Everything at or below it is "old" and is never tracked.
        // ========================================================

        if (!preferences.contains(KEY_BASELINE_ID)) {

            val newestId = findNewestImageId(resolver)

            if (newestId == null) {
                Log.e(TAG, "Could not read MediaStore. Baseline not created.")
                return DetectorOutcome.DONE
            }

            preferences.edit()
                .putLong(KEY_BASELINE_ID, newestId)
                .commit()

            Log.d(TAG, "FIRST RUN: baseline=$newestId. Nothing tracked.")
            return DetectorOutcome.DONE
        }

        var baselineId = preferences.getLong(KEY_BASELINE_ID, 0L)

        val selectedGroups = readSelectedGroups(context)

        if (selectedGroups.isEmpty()) {
            Log.d(TAG, "No selected groups. Nothing to do.")
            return DetectorOutcome.DONE
        }

        val processed =
            preferences.getStringSet(KEY_PROCESSED_IDS, emptySet())
                ?.toMutableSet() ?: mutableSetOf()

        val failures = loadFailures(preferences)

        val signatures = LinkedHashMap<String, Long>()

        (preferences.getString(KEY_SIGNATURES, "") ?: "")
            .split('\n')
            .forEach { line ->
                val p = line.split('\t', limit = 2)
                val t = p.getOrNull(0)?.toLongOrNull()
                if (p.size == 2 && t != null) signatures[p[1]] = t
            }

        // ========================================================
        // READ ALL NEW CANDIDATES (no per-run limit)
        // ========================================================

        val candidates = queryCandidates(resolver, baselineId)

        Log.d(
            TAG,
            "RUN baseline=$baselineId selectedGroups=${selectedGroups.size} newCandidates=${candidates.size}"
        )

        Diag.set(
            context,
            "run",
            "selected=${selectedGroups.size} newImages=${candidates.size}"
        )

        Diag.set(context, "newest", describeNewest(resolver, baselineId))

        var retry = false

        try {

            for (c in candidates) {

                val key = c.id.toString()

                if (processed.contains(key)) continue

                // Own sent images: never managed.
                if (isWhatsAppSentMedia(c.path)) {
                    Log.d(TAG, "Ignoring own sent image: ${c.name}")
                    processed.add(key)
                    continue
                }

                if (!isWhatsAppMedia(c.owner, c.path, c.name)) {
                    processed.add(key)
                    continue
                }

                // Same file registered twice by WhatsApp: already tracked, skip.
                val signature = "${c.name}|${c.size}"

                if (isRecentDuplicate(signatures, signature)) {
                    Log.d(TAG, "Duplicate MediaStore row for ${c.name}. Skipped.")
                    processed.add(key)
                    continue
                }

                // Wait until the matching window has closed, so a notification
                // from another chat arriving a few seconds later is still seen.
                val now = System.currentTimeMillis()

                if (now < c.addedMs + MATCH_AFTER_MS) {
                    retry = true
                    continue
                }

                // ====================================================
                // WHICH GROUP DID THIS IMAGE COME FROM?
                // ====================================================

                val chats = GroupEventStore.chatsBetween(
                    context,
                    c.addedMs - MATCH_BEFORE_MS,
                    c.addedMs + MATCH_AFTER_MS
                )

                if (chats.size != 1) {
                    Log.d(
                        TAG,
                        "UNASSIGNED (${chats.size} chats matched): ${c.name}. Skipped, not guessed."
                    )
                    Diag.set(
                        context,
                        "result",
                        "SKIPPED ${c.name}: ${chats.size} chats matched (no notification found)"
                    )
                    processed.add(key)
                    continue
                }

                val chat = chats.first()

                if (!chat.isGroup) {
                    Log.d(TAG, "Direct chat image (${chat.name}). Ignored.")
                    Diag.set(context, "result", "IGNORED ${c.name}: not recognised as a group (${chat.name})")
                    processed.add(key)
                    continue
                }

                val targetGroup =
                    selectedGroups.firstOrNull {
                        it.trim().equals(chat.name.trim(), ignoreCase = true)
                    }

                if (targetGroup == null) {
                    Log.d(TAG, "Group '${chat.name}' is not selected. Ignored: ${c.name}")
                    Diag.set(context, "result", "IGNORED ${c.name}: group '${chat.name}' is not selected")
                    processed.add(key)
                    continue
                }

                // ====================================================
                // TRACK THE ORIGINAL IMAGE (NO COPY IS MADE)
                // ====================================================

                val sourceUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    c.id
                )

                // Same picture content already tracked (even under another
                // name or MediaStore ID)? Then skip it.
                val contentKey = contentHash(context.contentResolver, sourceUri)
                    ?.let { "h:$it" }

                if (contentKey != null && isRecentDuplicate(signatures, contentKey)) {
                    Log.d(TAG, "Same content already tracked (${c.name}). Skipped.")
                    processed.add(key)
                    continue
                }

                val success = trackOriginal(
                    context = context,
                    mediaId = c.id,
                    displayName = c.name,
                    groupName = targetGroup
                )

                if (success) {

                    processed.add(key)
                    failures.remove(c.id)
                    val trackedAt = System.currentTimeMillis()
                    signatures[signature] = trackedAt
                    if (contentKey != null) signatures[contentKey] = trackedAt

                    Log.d(TAG, "TRACKED: ${c.name} id=${c.id} path=${c.path} -> $targetGroup")
                    Diag.set(context, "result", "TRACKED ${c.name} -> $targetGroup")

                } else {

                    val count = (failures[c.id] ?: 0) + 1
                    failures[c.id] = count

                    Log.e(TAG, "FAILED: ${c.name} failure=$count/$MAX_FAILS")

                    if (count < MAX_FAILS) {
                        retry = true
                    } else {
                        processed.add(key)
                        failures.remove(c.id)
                        Log.e(TAG, "Giving up on id=${c.id}. Original untouched.")
                    }
                }
            }

        } finally {

            // Always persist state, even if something above threw.
            if (processed.size > MAX_TRACKED) {

                val sorted = processed.mapNotNull { it.toLongOrNull() }.sorted()
                val drop = sorted.take(sorted.size - KEEP_TRACKED)

                if (drop.isNotEmpty()) {
                    baselineId = maxOf(baselineId, drop.last())
                    processed.removeAll(drop.map { it.toString() }.toSet())
                }
            }

            val trimmedSignatures =
                signatures.entries
                    .toList()
                    .takeLast(MAX_SIGNATURES)
                    .joinToString("\n") { "${it.value}\t${it.key}" }

            preferences.edit()
                .putString(KEY_SIGNATURES, trimmedSignatures)
                .putLong(KEY_BASELINE_ID, baselineId)
                .putStringSet(KEY_PROCESSED_IDS, processed)
                .putStringSet(
                    KEY_FAIL_COUNTS,
                    failures.map { "${it.key}:${it.value}" }.toSet()
                )
                .commit()
        }

        return if (retry) DetectorOutcome.RETRY else DetectorOutcome.DONE
    }

    // ============================================================
    // QUERY
    // ============================================================

    private fun queryCandidates(
        resolver: ContentResolver,
        baselineId: Long
    ): List<Candidate> {

        val result = mutableListOf<Candidate>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )

        val selection =
            "${MediaStore.Images.Media._ID} > ? " +
                    "AND ${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/%' " +
                    "AND (${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL " +
                    "OR ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?) " +
                    "AND (${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? " +
                    "OR ((${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL " +
                    "OR ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = '') " +
                    "AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) " +
                    "OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'IMG-%-WA%')"

        val args = arrayOf(
            baselineId.toString(),
            "Pictures/SmartGallery/%",
            WHATSAPP_PACKAGE,
            "%WhatsApp%"
        )

        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media._ID} ASC"
        )?.use { cursor ->

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val ownerCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                result.add(
                    Candidate(
                        id = cursor.getLong(idCol),
                        name = cursor.getString(nameCol) ?: "image.jpg",
                        mime = cursor.getString(mimeCol) ?: "image/jpeg",
                        owner = cursor.getString(ownerCol),
                        path = cursor.getString(pathCol),
                        addedMs = cursor.getLong(addedCol) * 1000L,
                        size = cursor.getLong(sizeCol)
                    )
                )
            }
        }

        return result
    }

    /** The 3 newest image files after the baseline, whatever app made them. */
    private fun describeNewest(
        resolver: ContentResolver,
        baselineId: Long
    ): String {

        return try {

            val parts = mutableListOf<String>()

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH
                ),
                "${MediaStore.Images.Media._ID} > ?",
                arrayOf(baselineId.toString()),
                "${MediaStore.Images.Media._ID} DESC"
            )?.use { cursor ->

                var count = 0

                while (cursor.moveToNext() && count < 3) {
                    count++
                    parts.add(
                        "${cursor.getString(1)} owner=${cursor.getString(2)} path=${cursor.getString(3)}"
                    )
                }
            }

            if (parts.isEmpty()) "no new files" else parts.joinToString(" | ")

        } catch (e: Exception) {
            "error: ${e.message}"
        }
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

    /** Newest image ID of ANY image (not only WhatsApp). Null if unreadable. */
    private fun findNewestImageId(
        resolver: ContentResolver
    ): Long? {

        var newest: Long? = null

        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media._ID} DESC"
        ) ?: return null

        cursor.use {
            newest = if (it.moveToFirst()) it.getLong(0) else 0L
        }

        return newest
    }

    // ============================================================
    // WHATSAPP VERIFICATION
    // ============================================================

    private fun isWhatsAppSentMedia(relativePath: String?): Boolean {

        if (relativePath.isNullOrBlank()) return false

        val normalized = relativePath.lowercase()

        return normalized.contains("/sent/") ||
                normalized.endsWith("/sent") ||
                normalized.contains("whatsapp images/sent")
    }

    private val WA_NAME = Regex("^IMG-\\d{8}-WA\\d+\\.\\w+$", RegexOption.IGNORE_CASE)

    private fun isWhatsAppMedia(
        ownerPackage: String?,
        relativePath: String?,
        displayName: String?
    ): Boolean {

        if (ownerPackage.equals(WHATSAPP_PACKAGE, ignoreCase = true)) return true

        if (ownerPackage.isNullOrBlank() &&
            relativePath?.lowercase()?.contains("whatsapp") == true
        ) {
            return true
        }

        // WhatsApp names its images like IMG-20261004-WA0019.jpg
        return displayName != null && WA_NAME.matches(displayName)
    }

    // ============================================================
    // DUPLICATE HELPERS
    // ============================================================

    private fun isRecentDuplicate(
        signatures: Map<String, Long>,
        key: String
    ): Boolean {
        val savedAt = signatures[key] ?: return false
        return System.currentTimeMillis() - savedAt < DEDUPE_WINDOW_MS
    }

    /** SHA-256 of the image bytes, or null if the file can't be read. */
    private fun contentHash(
        resolver: ContentResolver,
        uri: Uri
    ): String? {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadFailures(
        preferences: android.content.SharedPreferences
    ): MutableMap<Long, Int> {

        val map = mutableMapOf<Long, Int>()

        preferences.getStringSet(KEY_FAIL_COUNTS, emptySet())?.forEach { record ->
            val parts = record.split(":")
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                val count = parts[1].toIntOrNull()
                if (id != null && count != null) map[id] = count
            }
        }

        return map
    }

    // ============================================================
    // TRACK THE ORIGINAL IMAGE
    //
    // NO copy, NO move, NO file is created. Only the original MediaStore id and
    // its expiry time are stored. The expiry comes from the group's retention
    // setting and is fixed at the moment of detection.
    // ============================================================

    private fun trackOriginal(
        context: Context,
        mediaId: Long,
        displayName: String,
        groupName: String
    ): Boolean {

        return try {

            val preferences =
                context.getSharedPreferences("smart_gallery_settings", Context.MODE_PRIVATE)

            // Default is 7 to match the default shown in the Groups screen.
            val retention = preferences.getInt("retention_$groupName", 7)

            val now = System.currentTimeMillis()

            val expiresAt =
                when (retention) {
                    5 -> now + TimeUnit.MINUTES.toMillis(5)      // test mode: 5 minutes
                    1 -> now + TimeUnit.DAYS.toMillis(1)
                    7 -> now + TimeUnit.DAYS.toMillis(7)
                    15 -> now + TimeUnit.DAYS.toMillis(15)
                    30 -> now + TimeUnit.DAYS.toMillis(30)
                    else -> now + TimeUnit.DAYS.toMillis(7)
                }

            // Returns false (and changes nothing) if this id is already tracked.
            val added = SmartGalleryStorage.registerOriginal(
                context = context,
                mediaId = mediaId,
                displayName = displayName,
                groupName = groupName,
                expiresAt = expiresAt
            )

            Log.d(
                TAG,
                if (added) "REGISTERED original id=$mediaId group=$groupName retention=$retention"
                else "Original id=$mediaId was already registered. Timer unchanged."
            )

            // Run the expiry check right when this image expires (not only
            // every 15 minutes), so automatic deletion happens on time.
            if (added) {

                val delayMs =
                    (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L) + 5_000L

                val expiryWork =
                    OneTimeWorkRequestBuilder<MediaExpiryWorker>()
                        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                        .build()

                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        "expiry_$mediaId",
                        ExistingWorkPolicy.KEEP,
                        expiryWork
                    )
            }

            true

        } catch (exception: Exception) {

            Log.e(TAG, "Could not register original id=$mediaId: ${exception.message}", exception)

            false
        }
    }
}