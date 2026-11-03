package com.smartgallery.app

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class WhatsAppMediaWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(
    appContext,
    workerParams
) {

    override fun doWork(): Result {

        val isWatch = inputData.getBoolean(KEY_WATCH, false)
        val slot = inputData.getInt(KEY_SLOT, 0)

        /*
         * Re-arm the MediaStore watcher FIRST, in the OTHER slot.
         *
         * Old code re-armed under the same unique name while this run was still
         * active (APPEND_OR_REPLACE). That chained the new watcher BEHIND the
         * running one, and a chained work does not observe MediaStore changes
         * until its predecessor finishes - so images arriving during a run were
         * missed. Two alternating names mean the next watcher is armed
         * independently and starts observing immediately.
         */
        if (isWatch) {
            enqueueWatch(
                applicationContext,
                slot = 1 - slot,
                keepExisting = false
            )
        }

        try {

            when (
                WhatsAppMediaDetector.checkForNewWhatsAppImages(applicationContext)
            ) {

                WhatsAppMediaDetector.DetectorOutcome.MORE -> {
                    enqueueDrain(applicationContext, 0L)
                }

                // Images still waiting for their notification window to close,
                // or a copy that failed temporarily.
                WhatsAppMediaDetector.DetectorOutcome.RETRY -> {
                    enqueueDrain(applicationContext, 30L)
                }

                WhatsAppMediaDetector.DetectorOutcome.DONE -> {
                }
            }

        } catch (exception: Exception) {

            Log.e("WhatsAppWorker", "Detector error", exception)
        }

        return Result.success()
    }

    companion object {

        private const val KEY_WATCH = "watch"
        private const val KEY_SLOT = "slot"

        private const val OLD_WATCH_NAME = "whatsapp_media_watch"
        private const val WATCH_NAME_PREFIX = "whatsapp_media_watch_slot"
        private const val DRAIN_NAME = "whatsapp_media_drain"
        private const val KICK_NAME = "whatsapp_media_kick"
        private const val PERIODIC_NAME = "whatsapp_media_periodic"

        // ========================================================
        // START
        // ========================================================

        fun start(context: Context) {

            val appContext = context.applicationContext

            // Remove the watcher chain created by the previous version.
            WorkManager.getInstance(appContext).cancelUniqueWork(OLD_WATCH_NAME)

            enqueueWatch(appContext, slot = 0, keepExisting = true)

            // 15-minute fallback if an OEM drops MediaStore triggers.
            val periodic =
                PeriodicWorkRequestBuilder<WhatsAppMediaWorker>(
                    15,
                    TimeUnit.MINUTES
                ).build()

            WorkManager.getInstance(appContext)
                .enqueueUniquePeriodicWork(
                    PERIODIC_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodic
                )

            enqueueDrain(appContext, 0L)
        }

        /**
         * Called by GroupNotificationListener when a WhatsApp photo
         * notification arrives. Runs the detector just after the matching
         * window closes so the image is attributed promptly.
         */
        fun scheduleKick(context: Context) {

            val delay = (WhatsAppMediaDetector.MATCH_AFTER_MS / 1000L) + 2L

            val request =
                OneTimeWorkRequestBuilder<WhatsAppMediaWorker>()
                    .setInitialDelay(delay, TimeUnit.SECONDS)
                    .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    KICK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }

        // ========================================================
        // MEDIASTORE WATCHER
        // ========================================================

        private fun enqueueWatch(
            context: Context,
            slot: Int,
            keepExisting: Boolean
        ) {

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            val constraints =
                Constraints.Builder()
                    .addContentUriTrigger(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        true
                    )
                    .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(10, TimeUnit.SECONDS)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<WhatsAppMediaWorker>()
                    .setConstraints(constraints)
                    .setInputData(
                        Data.Builder()
                            .putBoolean(KEY_WATCH, true)
                            .putInt(KEY_SLOT, slot)
                            .build()
                    )
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "${WATCH_NAME_PREFIX}_$slot",
                    if (keepExisting) ExistingWorkPolicy.KEEP
                    else ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        // ========================================================
        // DRAIN
        // ========================================================

        private fun enqueueDrain(
            context: Context,
            delaySeconds: Long
        ) {

            val request =
                OneTimeWorkRequestBuilder<WhatsAppMediaWorker>()
                    .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    DRAIN_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
        }
    }
}