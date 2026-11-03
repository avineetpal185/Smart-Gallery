package com.smartgallery.app

import android.app.Activity
import android.app.RecoverableSecurityException
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi

/**
 * Deletes expired ORIGINAL WhatsApp images - only from the open app, and only
 * after Android's own confirmation screen.
 *
 *  Android 11+ : MediaStore.createDeleteRequest() -> one system dialog for a batch.
 *  Android 10  : delete() throws RecoverableSecurityException -> system dialog per image.
 *
 * A background Worker can NOT do this silently (WhatsApp owns these files), so the
 * Worker only marks images as "pending" and this class is run when the app is open.
 *
 * Create it in MainActivity.onCreate() (before the screen starts).
 */
class ExpiredImageDeleter(
    private val activity: ComponentActivity
) {

    private companion object {
        const val TAG = "ExpiredDeleter"
        const val MAX_BATCH = 100
    }

    private val launcher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            onResult(result.resultCode == Activity.RESULT_OK)
        }

    private var busy = false
    private var requestedCount = 0

    // Android 10 only
    private val legacyQueue = ArrayDeque<Uri>()
    private var legacyCurrent: Uri? = null

    // ============================================================
    // START: mark expired images, then ask Android to delete them.
    // Safe to call many times; does nothing if a request is already open
    // or nothing is waiting.
    // ============================================================
    fun start() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (busy) return

        busy = true

        Thread {

            val uris =
                try {
                    SmartGalleryStorage.markExpiredAsPending(activity.applicationContext)

                    // Silent delete if "Media management" is allowed (Android 12+).
                    SmartGalleryStorage.deletePendingSilently(activity.applicationContext)

                    // Whatever is left needs Android's approval screen.
                    SmartGalleryStorage.pendingUris(activity.applicationContext)
                } catch (exception: Exception) {
                    Log.e(TAG, "Could not read pending images: ${exception.message}")
                    emptyList()
                }

            activity.runOnUiThread {

                if (uris.isEmpty() || activity.isFinishing || activity.isDestroyed) {
                    busy = false
                    return@runOnUiThread
                }

                requestedCount = minOf(uris.size, MAX_BATCH)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestBatch(uris)
                } else {
                    startLegacy(uris)
                }
            }

        }.start()
    }

    // ============================================================
    // ANDROID 11+
    // ============================================================
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestBatch(uris: List<Uri>) {

        val batch = uris.take(MAX_BATCH)

        try {

            val pendingIntent =
                MediaStore.createDeleteRequest(activity.contentResolver, batch)

            launcher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )

            Log.d(TAG, "Asked Android to delete ${batch.size} expired image(s)")

        } catch (exception: Exception) {

            Log.e(TAG, "Delete request failed: ${exception.message}")
            busy = false
        }
    }

    // ============================================================
    // ANDROID 10
    // ============================================================
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startLegacy(uris: List<Uri>) {

        legacyQueue.clear()
        legacyQueue.addAll(uris.take(MAX_BATCH))
        legacyCurrent = null

        nextLegacy()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun nextLegacy() {

        while (true) {

            val uri = legacyQueue.removeFirstOrNull() ?: break

            try {

                // Works if we already have access; 0 rows = already gone.
                activity.contentResolver.delete(uri, null, null)

            } catch (exception: RecoverableSecurityException) {

                legacyCurrent = uri

                try {

                    launcher.launch(
                        IntentSenderRequest.Builder(
                            exception.userAction.actionIntent.intentSender
                        ).build()
                    )

                    return

                } catch (launchError: Exception) {
                    Log.e(TAG, "Could not open approval screen: ${launchError.message}")
                    legacyCurrent = null
                }

            } catch (exception: Exception) {

                // Keep it pending; it will be offered again later.
                Log.e(TAG, "Could not delete $uri: ${exception.message}")
            }
        }

        finishRun(continueIfProgress = false)
    }

    // ============================================================
    // RESULT OF ANDROID'S CONFIRMATION SCREEN
    // ============================================================
    private fun onResult(approved: Boolean) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            if (!approved) {
                Toast.makeText(
                    activity,
                    "Deletion cancelled. Expired images stay until you approve.",
                    Toast.LENGTH_LONG
                ).show()
            }

            finishRun(continueIfProgress = approved)
            return
        }

        // Android 10
        val uri = legacyCurrent
        legacyCurrent = null

        if (approved && uri != null) {

            try {
                activity.contentResolver.delete(uri, null, null)
            } catch (exception: Exception) {
                Log.e(TAG, "Delete after approval failed: ${exception.message}")
            }

            nextLegacy()

        } else {

            legacyQueue.clear()

            Toast.makeText(
                activity,
                "Deletion cancelled. Expired images stay until you approve.",
                Toast.LENGTH_LONG
            ).show()

            finishRun(continueIfProgress = false)
        }
    }

    // ============================================================
    // Remove records only for originals that are really gone.
    // If more expired images are waiting and the last round made progress,
    // ask again for the next batch.
    // ============================================================
    private fun finishRun(continueIfProgress: Boolean) {

        val before = requestedCount

        Thread {

            val left =
                try {
                    SmartGalleryStorage.reconcile(activity.applicationContext)
                } catch (exception: Exception) {
                    -1
                }

            activity.runOnUiThread {

                busy = false

                if (continueIfProgress && left > 0 && before >= MAX_BATCH) {
                    start()
                }
            }

        }.start()
    }
}