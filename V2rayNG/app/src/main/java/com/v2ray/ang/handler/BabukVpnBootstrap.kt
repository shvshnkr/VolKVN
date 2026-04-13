package com.v2ray.ang.handler

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object BabukVpnBootstrap {

    private const val TAG = "BabukVpnBootstrap"

    /**
     * One-shot refresh used from UI and worker.
     */
    suspend fun refreshServersAndSelectBest(context: Context) = withContext(Dispatchers.IO) {
        ensurePublicPoolSubscription(context)
        val merged = StringBuilder()
        for (raw in AppConfig.BABUK_SUBSCRIPTION_URLS) {
            val url = HttpUtil.toIdnUrl(raw.trim())
            val body = HttpUtil.getUrlContent(url, 30000) ?: continue
            val lines = body.count { it == '\n' } + 1
            Log.i(TAG, "Pool URL fetched: $lines lines, ${body.length} bytes -> $url")
            merged.appendLine(body)
        }
        val text = merged.toString().trim()
        if (text.isEmpty()) {
            Log.w(TAG, "No subscription content fetched")
            BabukDebugLog.log(context, TAG, "refresh: no subscription content")
            return@withContext
        }
        val (count, _) = AngConfigManager.importBatchConfig(text, AppConfig.BABUK_SUBSCRIPTION_ID, append = false)
        Log.i(TAG, "Imported $count endpoints from public pool")
        BabukDebugLog.log(context, TAG, "refresh: imported $count endpoints")
        if (count > 0) {
            BabukServerSelector.pickBestServer(AppConfig.BABUK_SUBSCRIPTION_ID)
        }
    }

    /**
     * Registers the pool in the subscription list (named group for built-in pool).
     */
    fun ensurePublicPoolSubscription(context: Context) {
        val item = SubscriptionItem(
            remarks = context.getString(R.string.babuk_subscription_remarks),
            url = AppConfig.BABUK_SUBSCRIPTION_URLS.joinToString("\n"),
            enabled = true,
            autoUpdate = true,
        )
        MmkvManager.encodeSubscription(AppConfig.BABUK_SUBSCRIPTION_ID, item)
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, AppConfig.BABUK_SUBSCRIPTION_ID)
    }

    fun schedulePublicPoolWorker() {
        val rw = RemoteWorkManager.getInstance(AngApplication.application)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<BabukPublicPoolWorker>(60, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        rw.enqueueUniquePeriodicWork(
            AppConfig.BABUK_PUBLIC_POOL_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    /**
     * Simple mode: split tunnel (per-app allowlist) — same defaults as stable 1.0.3-babuk; hev-tun off (no bundled .so).
     */
    fun applySimpleModeDefaults(context: Context) {
        ensurePublicPoolSubscription(context)
        // false = xray-core built-in TUN (no libhev-socks5-tunnel.so). Hev tunnel requires native libs from compile-hevtun.sh in app/libs — not shipped in this fork.
        MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, false)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, false)

        val pm = context.packageManager
        val candidates = linkedSetOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.youtube",
            "app.revanced.android.youtube",
        )
        val allowed = mutableSetOf<String>()
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                allowed.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        if (allowed.isNotEmpty()) {
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, allowed)
        }
        BabukDebugLog.log(
            context,
            TAG,
            "applySimpleModeDefaults hev=false perApp=true packages=${allowed.size}: ${allowed.joinToString()}",
        )
    }
}
