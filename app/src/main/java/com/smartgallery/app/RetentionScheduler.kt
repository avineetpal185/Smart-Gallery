package com.smartgallery.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object RetentionScheduler {

    private const val WORK_NAME =
        "smart_gallery_retention"

    fun start(context: Context) {

        val workManager =
            WorkManager.getInstance(context)

        val workRequest =
            PeriodicWorkRequestBuilder<MediaExpiryWorker>(
                15,
                TimeUnit.MINUTES
            )
                .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        println("================================")
        println("SMART GALLERY RETENTION STARTED")
        println("Automatic expiry check enabled")
        println("Checks every 15 minutes")
        println("================================")
    }
}