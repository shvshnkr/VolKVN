package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.extension.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Simple-mode connect pipeline: network probe → prepare → start → verify (Dahusim BaseService sequence).
 */
object VolkvnSimpleModeConnectOrchestrator {

    private const val CORE_RUNNING_WAIT_MS = 15_000L
    private const val CORE_RUNNING_POLL_MS = 100L

    suspend fun connect(context: Context, startVpn: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        val reach = VolkvnSimpleModeNetwork.probeAndApply(context)
        if (!reach.hasInternet) {
            withContext(Dispatchers.Main) {
                context.toast(R.string.volkvn_status_no_internet)
            }
            return@withContext false
        }
        if (!reach.googleReachable && !reach.whitelistOnly) {
            MmkvManager.setAutoConnectPausedUntilGoogle(true)
            VolkvnDebugLog.simpleModeLog("8", "connect_paused_no_google")
            withContext(Dispatchers.Main) {
                context.toast(R.string.volkvn_status_paused_until_google)
            }
            return@withContext false
        }
        MmkvManager.setAutoConnectPausedUntilGoogle(false)

        runCatching {
            VolkvnVpnBootstrap.refreshServersAndSelectBest(context, skipPickIfRecent = true)
        }

        when (val prep = VolkvnServerSelector.prepareForConnect(context)) {
            PrepareForConnectResult.NoProfiles -> {
                withContext(Dispatchers.Main) { context.toast(R.string.volkvn_simple_no_profile) }
                return@withContext false
            }
            PrepareForConnectResult.AllProbesDead -> {
                withContext(Dispatchers.Main) { context.toast(R.string.volkvn_status_all_probes_dead) }
                return@withContext false
            }
            is PrepareForConnectResult.Success -> {
                MmkvManager.setSelectServer(prep.guid)
            }
        }

        SimpleModeStatusStore.setFromStringRes(context, R.string.volkvn_status_connecting)
        withContext(Dispatchers.Main) { startVpn() }
        true
    }

    suspend fun verifyAfterStart(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!waitForCoreRunning()) {
            VolkvnDebugLog.simpleModeLog("9", "verify_skip_not_running")
            return@withContext false
        }
        val guid = MmkvManager.getSelectServer() ?: run {
            VolkvnDebugLog.simpleModeLog("9", "verify_skip_no_guid")
            return@withContext false
        }
        VolkvnDebugLog.simpleModeLog("9", "verify_start guidLen=${guid.length}")
        if (MmkvManager.getVpnExitProbeProfileId() != guid) {
            VolkvnVpnExitProbe.clearCache()
        }
        VolkvnVpnExitProbe.probeAndStore(guid)
        if (VolkvnWhitelistRuRouting.shouldRouteRuGeoViaProxy(guid)) {
            VolkvnDebugLog.simpleModeLog("9", "verify_wl_ru_routing_restart")
            withContext(Dispatchers.Main) {
                MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
            }
            return@withContext true
        }
        val speedConfig = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!speedConfig.status) {
            VolkvnDebugLog.simpleModeLog("9", "verify_config_failed")
            return@withContext tryRecover(context, guid)
        }
        V2RayNativeManager.initCoreEnv(context.applicationContext)
        val delay = V2RayNativeManager.measureOutboundDelay(
            speedConfig.content,
            SettingsManager.getDelayTestUrlForConnect(),
        )
        if (delay < 0) {
            VolkvnDebugLog.simpleModeLog("9", "verify_url_test_failed")
            return@withContext tryRecover(context, guid)
        }
        VolkvnAutoSelectProbePolicy.recordPostConnectUrlVerified(guid)
        VolkvnServerSelector.markConnected(guid)
        withContext(Dispatchers.Main) { SimpleModeStatusStore.setConnected(context) }
        VolkvnDebugLog.simpleModeLog("9", "verify_ok delayMs=$delay")
        true
    }

    private suspend fun tryRecover(context: Context, failedGuid: String): Boolean {
        val wl = MmkvManager.isActiveWhitelistRestrictedNetwork()
        if (wl) {
            SimpleModeStatusStore.setFromStringRes(context, R.string.volkvn_status_wl_trying_next)
            VolkvnServerSelector.markServerUnhealthy(failedGuid, "post_connect_verify_failed")
            val prep = VolkvnServerSelector.prepareForConnect(context, networkHandoff = true)
            if (prep is PrepareForConnectResult.Success && prep.guid != failedGuid) {
                MmkvManager.setSelectServer(prep.guid)
                withContext(Dispatchers.Main) {
                    V2RayServiceManager.stopVService(context)
                    V2RayServiceManager.startVServiceFromToggle(context)
                }
                return true
            }
        }
        val fallback = VolkvnServerSelector.tryMoveToFallback(failedGuid)
        if (fallback != null) {
            withContext(Dispatchers.Main) {
                V2RayServiceManager.stopVService(context)
                V2RayServiceManager.startVServiceFromToggle(context)
            }
            return true
        }
        return false
    }

    private suspend fun waitForCoreRunning(): Boolean {
        var waited = 0L
        while (!V2RayServiceManager.isRunning() && waited < CORE_RUNNING_WAIT_MS) {
            delay(CORE_RUNNING_POLL_MS)
            waited += CORE_RUNNING_POLL_MS
        }
        return V2RayServiceManager.isRunning()
    }
}
