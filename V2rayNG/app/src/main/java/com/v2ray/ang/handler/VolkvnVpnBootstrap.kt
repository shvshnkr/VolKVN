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
import com.v2ray.ang.util.Utils
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object VolkvnVpnBootstrap {

    private const val TAG = "VolkvnVpnBootstrap"
    private val refreshMutex = Mutex()

    private fun checkSelectedRealHealthy(context: Context, guid: String, hypothesisId: String): Boolean {
        val speedConfig = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        val profile = MmkvManager.decodeServerConfig(guid)
        val host = profile?.server.orEmpty()
        val port = profile?.serverPort.orEmpty()
        if (!speedConfig.status) {
            // #region agent log
            VolkvnAgentDebug.emit(
                context,
                hypothesisId = hypothesisId,
                location = "VolkvnVpnBootstrap.kt:realPingCheck",
                message = "selected_real_ping_config_failed",
                data = mapOf("guidLen" to guid.length),
            )
            // #endregion
            return false
        }
        val primary = V2RayNativeManager.measureOutboundDelay(
            speedConfig.content,
            SettingsManager.getDelayTestUrlForConnect(),
        )
        val fallback = if (primary >= 0) primary else V2RayNativeManager.measureOutboundDelay(
            speedConfig.content,
            SettingsManager.getDelayTestUrlForConnect(true),
        )
        val delay = max(primary, fallback)
        // #region agent log
        VolkvnAgentDebug.emit(
            context,
            hypothesisId = hypothesisId,
            location = "VolkvnVpnBootstrap.kt:realPingCheck",
            message = "selected_real_ping_check",
            data = mapOf(
                "guid" to guid,
                "host" to host,
                "port" to port,
                "guidLen" to guid.length,
                "primaryDelayMs" to primary,
                "fallbackDelayMs" to fallback,
                "realHealthy" to (delay >= 0),
            ),
        )
        // #endregion
        return delay >= 0
    }

    /**
     * One-shot refresh used from UI and worker.
     * - Debounced a few seconds to avoid duplicate imports from tight UI + worker scheduling.
     * - If the selected profile is still in the pool after import, do not [pickBestServer] (avoids
     *   MMKV/core mismatch while the core keeps the old config, and avoids re-picking on every refresh
     *   while VPN is down — that used to reshuffle [VolkvnServerSelector] and replace a decent node
     *   with a worse TCP-only probe right before connect).
     * - [pickBestServer] runs when selection is blank or no longer in the imported pool; dead nodes
     *   are still handled via [VolkvnServerSelector.markServerUnhealthy], watchdog, and auto-recover.
     */
    suspend fun refreshServersAndSelectBest(context: Context, skipPickIfRecent: Boolean = false) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val reach = VolkvnSimpleModeNetwork.probeAndApply(context, fast = true)
            if (!reach.hasInternet) {
                VolkvnDebugLog.log(context, TAG, "refresh: skip no internet")
                return@withContext
            }
            val nowWall = System.currentTimeMillis()
            val lastWall = MmkvManager.decodeSettingsLong(AppConfig.PREF_VOLKVN_LAST_POOL_REFRESH_AT, 0L)
            val deltaMs = if (lastWall > 0L) nowWall - lastWall else -1L
            // #region agent log
            VolkvnAgentDebug.emit(
                context,
                hypothesisId = "H2",
                location = "VolkvnVpnBootstrap.kt:refreshServersAndSelectBest",
                message = "refresh_enter",
                data = mapOf(
                    "lastWall" to lastWall,
                    "nowWall" to nowWall,
                    "deltaMs" to deltaMs,
                    "willSkipDebounce" to (lastWall > 0L && nowWall - lastWall < 4000L),
                ),
            )
            // #endregion
            if (lastWall > 0L && nowWall - lastWall < 4000L) {
                VolkvnDebugLog.log(context, TAG, "refresh: skip debounce ${nowWall - lastWall}ms")
                // #region agent log
                VolkvnAgentDebug.emit(
                    context,
                    hypothesisId = "H2",
                    location = "VolkvnVpnBootstrap.kt:skipDebounce",
                    message = "refresh_skipped_no_import_no_pick",
                    data = mapOf("deltaMs" to (nowWall - lastWall)),
                )
                // #endregion
                return@withContext
            }

            ensurePublicPoolSubscription(context)
            VolkvnBuiltinBootstrap.ensureBuiltinHelpers(context)
            val merged = StringBuilder()
            val wlRestricted = MmkvManager.isActiveWhitelistRestrictedNetwork()
            val vpnUp = Utils.isVpnTransportActive(context.applicationContext)
            val poolUrls = allPoolSourceUrls(context)
            poolUrls.forEachIndexed { index, raw ->
                SimpleModeStatusStore.setProgress(
                    context,
                    R.string.volkvn_status_refreshing_subs_progress,
                    index + 1,
                    poolUrls.size,
                )
                val url = HttpUtil.toIdnUrl(raw.trim())
                val fetchLink = VolkvnWhitelistSubscriptionFetch.resolveFetchLink(raw.trim(), wlRestricted, vpnUp)
                val rawBody = HttpUtil.getUrlContent(fetchLink, 30000) ?: return@forEachIndexed
                val body = VolkvnWhitelistSubscriptionFetch.extractSubscriptionBody(rawBody)
                val lines = body.count { it == '\n' } + 1
                Log.i(TAG, "Pool URL fetched: $lines lines, ${body.length} bytes -> $url")
                merged.appendLine(body)
            }
            SimpleModeStatusStore.flushPendingProgress()
            val text = merged.toString().trim()
            if (text.isEmpty()) {
                Log.w(TAG, "No subscription content fetched")
                VolkvnDebugLog.log(context, TAG, "refresh: no subscription content")
                return@withContext
            }
            val (count, _) = AngConfigManager.importBatchConfig(text, AppConfig.VOLKVN_SUBSCRIPTION_ID, append = false)
            Log.i(TAG, "Imported $count endpoints from public pool")
            VolkvnDebugLog.log(context, TAG, "refresh: imported $count endpoints")
            if (count <= 0) return@withContext

            MmkvManager.encodeSettings(AppConfig.PREF_VOLKVN_LAST_POOL_REFRESH_AT, System.currentTimeMillis())

            val subId = AppConfig.VOLKVN_SUBSCRIPTION_ID
            // Pool re-import replaces serverList order; restore ping order using stored test results.
            MmkvManager.sortServerListByTestDelay(subId)
            // #region agent log
            VolkvnAgentDebug.emit(
                context,
                hypothesisId = "H33",
                location = "VolkvnVpnBootstrap.kt:afterPoolImport",
                message = "pool_list_resorted_by_stored_ping",
                data = mapOf("subId" to subId),
            )
            // #endregion
            val selected = MmkvManager.getSelectServer()
            val guids = MmkvManager.decodeServerList(subId)
            val mergedPoolGuids = VolkvnBuiltinBootstrap.mergePublicAndBuiltinGuids().toSet()
            val selectedInPool = selected != null && selected in mergedPoolGuids
            val selectedHealthyWhenDown =
                if (!vpnUp && selectedInPool) VolkvnServerSelector.isServerTcpHealthy(context, selected, attempts = 2) else true
            val selectedRealHealthyWhenDown =
                if (!vpnUp && selectedInPool && selectedHealthyWhenDown) {
                    checkSelectedRealHealthy(context, selected, "H17")
                } else {
                    true
                }
            val needPick = !skipPickIfRecent && (
                selected.isNullOrBlank() || !selectedInPool || !selectedHealthyWhenDown || !selectedRealHealthyWhenDown
                )
            // #region agent log
            VolkvnAgentDebug.emit(
                context,
                hypothesisId = "H5",
                location = "VolkvnVpnBootstrap.kt:afterImport",
                message = "needPick_decision",
                    data = mapOf(
                    "importCount" to count,
                    "guidsSize" to guids.size,
                    "mergedPoolSize" to mergedPoolGuids.size,
                    "selectedPresent" to !selected.isNullOrBlank(),
                    "selectedInPool" to selectedInPool,
                    "vpnUp" to vpnUp,
                    "selectedHealthyWhenDown" to selectedHealthyWhenDown,
                    "selectedRealHealthyWhenDown" to selectedRealHealthyWhenDown,
                    "needPick" to needPick,
                ),
            )
            // #endregion
            if (needPick) {
                if (!selected.isNullOrBlank() && selectedInPool && (!selectedHealthyWhenDown || !selectedRealHealthyWhenDown)) {
                    VolkvnServerSelector.markServerUnhealthy(
                        selected,
                        "refresh:selected_failed_health tcp=$selectedHealthyWhenDown real=$selectedRealHealthyWhenDown",
                    )
                }
                when (val prep = VolkvnServerSelector.prepareForConnect(context)) {
                    is PrepareForConnectResult.Success -> {
                        MmkvManager.setSelectServer(prep.guid)
                        if (!vpnUp) {
                            val pickedRealHealthy = checkSelectedRealHealthy(context, prep.guid, "H21")
                            if (!pickedRealHealthy) {
                                VolkvnServerSelector.markServerUnhealthy(prep.guid, "refresh:post_pick_real_ping_failed")
                                when (val reprep = VolkvnServerSelector.prepareForConnect(context)) {
                                    is PrepareForConnectResult.Success ->
                                        MmkvManager.setSelectServer(reprep.guid)
                                    else -> Unit
                                }
                            }
                        }
                    }
                    PrepareForConnectResult.NoProfiles ->
                        VolkvnDebugLog.log(context, TAG, "refresh: prepareForConnect no profiles")
                    PrepareForConnectResult.AllProbesDead ->
                        VolkvnDebugLog.log(context, TAG, "refresh: prepareForConnect all probes dead")
                }
                VolkvnDebugLog.log(
                    context,
                    TAG,
                    "refresh: prepareForConnect (vpnUp=$vpnUp blank=${selected.isNullOrBlank()} missing=${!selectedInPool})",
                )
                // #region agent log
                VolkvnAgentDebug.emit(
                    context,
                    hypothesisId = "H5",
                    location = "VolkvnVpnBootstrap.kt:afterPrepare",
                    message = "selected_after_prepare",
                    data = mapOf(
                        "guid" to (MmkvManager.getSelectServer() ?: ""),
                    ),
                )
                // #endregion
            } else {
                VolkvnDebugLog.log(context, TAG, "refresh: keep selection guid=$selected (${guids.size} in pool)")
            }
        }
    }

    /**
     * Built-in URLs plus optional user lines from [AppConfig.PREF_VOLKVN_USER_POOL_URLS] (http/https only, deduped).
     */
    fun allPoolSourceUrls(context: Context): List<String> {
        val user = MmkvManager.decodeSettingsString(AppConfig.PREF_VOLKVN_USER_POOL_URLS, "").orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        val merged = ArrayList<String>(AppConfig.VOLKVN_SUBSCRIPTION_URLS.size + user.size)
        val seen = HashSet<String>()
        for (u in AppConfig.VOLKVN_SUBSCRIPTION_URLS) {
            if (seen.add(u)) merged.add(u)
        }
        for (u in user) {
            if (seen.add(u)) merged.add(u)
        }
        return merged
    }

    /**
     * Registers the pool in the subscription list (named group for built-in pool).
     */
    fun ensurePublicPoolSubscription(context: Context) {
        val item = SubscriptionItem(
            remarks = context.getString(R.string.volkvn_subscription_remarks),
            url = allPoolSourceUrls(context).joinToString("\n"),
            enabled = true,
            autoUpdate = true,
        )
        MmkvManager.encodeSubscription(AppConfig.VOLKVN_SUBSCRIPTION_ID, item)
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, AppConfig.VOLKVN_SUBSCRIPTION_ID)
    }

    fun schedulePublicPoolWorker() {
        val rw = RemoteWorkManager.getInstance(AngApplication.application)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<VolkvnPublicPoolWorker>(60, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        rw.enqueueUniquePeriodicWork(
            AppConfig.VOLKVN_PUBLIC_POOL_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    /**
     * Simple mode: split tunnel (per-app allowlist); hev-tun off (no bundled .so).
     */
    fun applySimpleModeDefaults(context: Context) {
        ensurePublicPoolSubscription(context)
        VolkvnBuiltinBootstrap.ensureBuiltinHelpers(context)
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
        VolkvnDebugLog.log(
            context,
            TAG,
            "applySimpleModeDefaults hev=false perApp=true packages=${allowed.size}: ${allowed.joinToString()}",
        )
    }
}
