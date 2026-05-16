package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.util.HttpUtil

/**
 * Per-subscription default URLs and migrations (Dahusim [DefaultUserBootstrap]).
 */
object VolkvnDefaultUserBootstrap {

    private const val AUTO_UPDATE_MINUTES = 60
    private const val STANDALONE_SE_SUB_ID = "__volkvn_se_standalone__"
    private const val brokenSwordwareTxtLink =
        "https://raw.githubusercontent.com/mbelspb-gif/dddddad/refs/heads/main/Swordware.txt"
    private const val swordwareVlessReserveLink =
        "https://raw.githubusercontent.com/mbelspb-gif/ffsfsfssdf/refs/heads/main/TG-swordware"

    private val defaultSubscriptionLinks = listOf(
        "https://mifa.world/vless",
        "https://mifa.world/hysteria",
        swordwareVlessReserveLink,
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS_mobile.txt",
        "https://gist.githubusercontent.com/flaafix/c79a81037d15163360571c7a7331b153/raw/AetrisVPN.txt",
        "https://raw.githubusercontent.com/nzea243/ikoV31tud_vpn/refs/heads/main/tri_228.txt",
        "https://raw.githubusercontent.com/HikaruApps/WhiteLattice/refs/heads/main/subscriptions/config.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/BlackList.txt",
        "https://wlrus.lol/confs/blackl.txt",
    )

    private fun quickSubId(index: Int): String = "__volkvn_quick_sub_${index}__"

    suspend fun bootstrapAll(context: Context) {
        if (MmkvManager.isVolkvnDefaultSubsBootstrapped()) {
            migrateBrokenSwordware()
            VolkvnBuiltinBootstrap.ensureBuiltinHelpers(context)
            return
        }
        bootstrapDefaultSubscriptions(context)
        ensureStandaloneSeProfile(context)
        VolkvnBuiltinBootstrap.ensureBuiltinHelpers(context)
        MmkvManager.setVolkvnDefaultSubsBootstrapped(true)
        VolkvnDebugLog.simpleModeLog("24", "default_subs_bootstrap_done")
    }

    private fun bootstrapDefaultSubscriptions(context: Context) {
        val existing = MmkvManager.decodeSubscriptions()
            .mapNotNull { it.subscription.url.takeIf { u -> u.isNotBlank() } }
            .toSet()

        defaultSubscriptionLinks.forEachIndexed { index, link ->
            if (link in existing) return@forEachIndexed
            val subId = quickSubId(index)
            val item = SubscriptionItem(
                remarks = context.getString(R.string.volkvn_quick_subscription_remarks, index + 1),
                url = link,
                enabled = true,
                autoUpdate = true,
                updateInterval = AUTO_UPDATE_MINUTES,
            )
            MmkvManager.encodeSubscription(subId, item)
            fetchSubscriptionOnce(subId, link)
        }
        migrateBrokenSwordware()
    }

    private fun ensureStandaloneSeProfile(context: Context) {
        val item = SubscriptionItem(
            remarks = context.getString(R.string.volkvn_se_standalone_remarks),
            url = "",
            enabled = true,
            autoUpdate = false,
        )
        MmkvManager.encodeSubscription(STANDALONE_SE_SUB_ID, item)
        AngConfigManager.importBatchConfig(
            VolkvnBuiltinVlessShareLines.standaloneSeVlessUri,
            STANDALONE_SE_SUB_ID,
            append = false,
        )
    }

    private fun migrateBrokenSwordware() {
        for (cache in MmkvManager.decodeSubscriptions()) {
            if (cache.subscription.url != brokenSwordwareTxtLink) continue
            cache.subscription.url = swordwareVlessReserveLink
            MmkvManager.encodeSubscription(cache.guid, cache.subscription)
            fetchSubscriptionOnce(cache.guid, swordwareVlessReserveLink)
            VolkvnDebugLog.simpleModeLog("24", "migrated_swordware sub=${cache.guid}")
        }
    }

    private fun fetchSubscriptionOnce(subId: String, link: String) {
        val wl = MmkvManager.isActiveWhitelistRestrictedNetwork()
        val fetchLink = VolkvnWhitelistSubscriptionFetch.resolveFetchLink(link, wl, vpnConnected = false)
        val raw = HttpUtil.getUrlContent(fetchLink, 30000) ?: return
        val body = VolkvnWhitelistSubscriptionFetch.extractSubscriptionBody(raw)
        if (body.isBlank()) return
        AngConfigManager.importBatchConfig(body, subId, append = false)
    }
}
