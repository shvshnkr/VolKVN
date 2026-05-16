package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * Builds candidate GUID lists for auto-select (Dahusim AutoServerSelector pool rules).
 */
object VolkvnServerPoolBuilder {

    fun isSubscriptionWhitelistMarked(remarks: String): Boolean {
        val n = remarks.lowercase()
        return n.contains("whitelist") || n.contains("white lists") || n.contains("white list")
    }

    fun subscriptionWhitelistGuids(): Set<String> {
        val out = HashSet<String>()
        for (cache in MmkvManager.decodeSubscriptions()) {
            if (isSubscriptionWhitelistMarked(cache.subscription.remarks)) {
                out.addAll(MmkvManager.decodeServerList(cache.guid))
            }
        }
        return out
    }

    /**
     * @param whitelistBuiltinOnly from network probe (dzen ok, google not)
     */
    fun buildCandidateGuids(whitelistBuiltinOnly: Boolean): List<String> {
        val all = MmkvManager.decodeAllServerList().distinct()
        if (all.isEmpty()) return emptyList()

        val wlSubGuids = subscriptionWhitelistGuids()
        if (!whitelistBuiltinOnly) {
            return all.filter { it !in wlSubGuids }
        }

        val builtin = VolkvnBuiltinBootstrap.stableGuidOrder()
        val builtinSet = builtin.toSet()
        val wlMarkedList = wlSubGuids.filter { it !in builtinSet }
        val priorityIds = builtinSet + wlMarkedList.toSet()
        val head = (builtin + wlMarkedList).distinct()
        val rest = all.filter { it !in priorityIds }
        return head + rest
    }

    fun priorityFirstIds(whitelistBuiltinOnly: Boolean): Set<String> {
        if (!whitelistBuiltinOnly) return emptySet()
        return VolkvnBuiltinBootstrap.whitelistOnlyStableGuids() + subscriptionWhitelistGuids()
    }

    fun isPublicPoolGuid(guid: String): Boolean =
        MmkvManager.decodeServerConfig(guid)?.subscriptionId == AppConfig.VOLKVN_SUBSCRIPTION_ID
}
