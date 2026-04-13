package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig

/**
 * Hourly (minimum 60m periodic interval on WorkManager) refresh of bundled public subscription URLs.
 */
class VolkvnPublicPoolWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            VolkvnVpnBootstrap.refreshServersAndSelectBest(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "VolkvnPublicPoolWorker failed", e)
            Result.retry()
        }
    }
}
