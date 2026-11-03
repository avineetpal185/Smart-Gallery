package com.smartgallery.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Background expiry check.
 *
 *  1. Marks expired images as "pending deletion".
 *  2. If the user allowed "Media management" (Android 12+), deletes them
 *     right here, silently.
 *  3. Otherwise they stay pending until the app is opened, because WhatsApp
 *     owns the files and Android then requires the user's approval.
 *
 * A Worker never opens a screen.
 */
class MediaExpiryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {

        return try {

            SmartGalleryStorage.markExpiredAsPending(
                applicationContext
            )

            SmartGalleryStorage.deletePendingSilently(
                applicationContext
            )

            Result.success()

        } catch (exception: Exception) {

            exception.printStackTrace()

            Result.retry()
        }
    }
}