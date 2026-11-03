package com.smartgallery.app

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * DISABLED.
 *
 * The old version downloaded images from a backend and SAVED COPIES into
 * Pictures/SmartGallery/. Smart Gallery now manages the ORIGINAL WhatsApp image
 * only and never creates copies, so this worker does nothing.
 *
 * The class is kept (empty) so any code that still refers to it keeps compiling.
 */
class MediaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {

        Log.d("SGSync", "MediaSyncWorker is disabled (no-copy mode).")

        return Result.success()
    }
}