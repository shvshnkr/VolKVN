package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.R

/**
 * Applies [NetworkReachability] to MMKV flags (Dahusim DataStore wl/open).
 */
object VolkvnSimpleModeNetwork {

    suspend fun probeAndApply(context: Context, fast: Boolean = false): NetworkReachability {
        SimpleModeStatusStore.setFromStringRes(context, R.string.volkvn_status_checking_network)
        val reach = NetworkReachabilityProbe.probe(context, fast)
        MmkvManager.setActiveWhitelistRestrictedNetwork(reach.whitelistOnly)
        if (reach.whitelistOnly) {
            MmkvManager.setSimpleModeUseWhitelistBuiltinPoolOnly(true)
        }
        if (!reach.googleReachable && !reach.whitelistOnly) {
            MmkvManager.setAutoConnectPausedUntilGoogle(true)
        } else if (reach.googleReachable && MmkvManager.isAutoConnectPausedUntilGoogle()) {
            MmkvManager.setAutoConnectPausedUntilGoogle(false)
        }
        VolkvnDebugLog.simpleModeLog(
            "7",
            "reachability google=${reach.googleReachable} dzen=${reach.dzenReachable} " +
                "ya=${reach.yaReachable} wlSrc=${reach.whitelistSourceReachable} wlOnly=${reach.whitelistOnly}",
        )
        return reach
    }

    fun consumeWhitelistBuiltinPoolOnlyFlag(): Boolean {
        val v = MmkvManager.getSimpleModeUseWhitelistBuiltinPoolOnly()
        MmkvManager.clearSimpleModeUseWhitelistBuiltinPoolOnly()
        return v
    }
}
