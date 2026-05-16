package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RulesetItem

/**
 * On whitelist-restricted networks with RU VPN exit, route RU geo bypass rules via proxy (Dahusim).
 */
object VolkvnWhitelistRuRouting {

    fun shouldRouteRuGeoViaProxy(selectedGuid: String?): Boolean {
        if (!MmkvManager.isActiveWhitelistRestrictedNetwork()) return false
        val probeId = MmkvManager.getVpnExitProbeProfileId() ?: return false
        if (selectedGuid != probeId) return false
        return MmkvManager.getVpnExitIsRussia() == true
    }

    fun isRuGeoDirectBypassRule(item: RulesetItem): Boolean {
        if (!item.enabled) return false
        if (item.outboundTag != AppConfig.TAG_DIRECT) return false
        val d = item.domain?.joinToString(",")?.lowercase().orEmpty()
        val ip = item.ip?.joinToString(",")?.lowercase().orEmpty()
        return d.contains("geosite:ru") || d.contains("geosite-category-ru") ||
            ip.contains("geoip:ru") || ip.contains(AppConfig.GEOIP_RU.lowercase())
    }
}
