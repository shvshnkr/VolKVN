package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Re-probe and restart VPN when wl↔open network class changes while connected (Dahusim coordinator).
 */
object VolkvnWhitelistNetworkState {

    private const val DEBOUNCE_MS = 2_500L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var adaptJob: Job? = null
    private var lastAdaptAt = 0L
    private var lastWlOnly: Boolean? = null

    fun onConnectivityMaybeChanged(context: Context) {
        if (!V2RayServiceManager.isRunning()) return
        adaptJob?.cancel()
        adaptJob = scope.launch {
            mutex.withLock {
                val now = System.currentTimeMillis()
                if (now - lastAdaptAt < DEBOUNCE_MS) return@withLock
                lastAdaptAt = now
                val reach = NetworkReachabilityProbe.probe(context, fast = true)
                val prev = lastWlOnly
                lastWlOnly = reach.whitelistOnly
                MmkvManager.setActiveWhitelistRestrictedNetwork(reach.whitelistOnly)
                if (prev != null && prev == reach.whitelistOnly) return@withLock
                VolkvnDebugLog.simpleModeLog("30", "network_handoff wl=${reach.whitelistOnly} prev=$prev")
                SimpleModeStatusStore.setFromStringRes(
                    context,
                    if (reach.whitelistOnly) {
                        R.string.volkvn_status_network_changed
                    } else {
                        R.string.volkvn_status_network_changed_open
                    },
                )
                VolkvnServerSelector.cancelInFlightPrepare()
                if (reach.whitelistOnly) {
                    MmkvManager.setSimpleModeUseWhitelistBuiltinPoolOnly(true)
                }
                val prep = VolkvnServerSelector.prepareForConnect(context, networkHandoff = true)
                when (prep) {
                    is PrepareForConnectResult.Success -> {
                        val cur = MmkvManager.getSelectServer()
                        if (prep.guid != cur) {
                            MmkvManager.setSelectServer(prep.guid)
                            V2RayServiceManager.stopVService(context)
                            V2RayServiceManager.startVServiceFromToggle(context)
                        } else {
                            SimpleModeStatusStore.clearActivity()
                        }
                    }
                    else -> SimpleModeStatusStore.clearActivity()
                }
            }
        }
    }

    fun reset() {
        lastWlOnly = null
        adaptJob?.cancel()
    }
}
