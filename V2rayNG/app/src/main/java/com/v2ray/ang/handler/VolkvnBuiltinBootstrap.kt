package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType

/**
 * Registers a fixed subscription group and stable GUID profiles for built-in Trojan helpers.
 */
object VolkvnBuiltinBootstrap {

    /** Stable GUIDs (must stay fixed for idempotent upgrades). */
    private val STABLE_GUIDS: Array<String> = arrayOf(
        "f0000001-0000-4000-8000-000000000001",
        "f0000001-0000-4000-8000-000000000002",
        "f0000001-0000-4000-8000-000000000003",
        "f0000001-0000-4000-8000-000000000004",
        "f0000001-0000-4000-8000-000000000005",
    )

    init {
        check(STABLE_GUIDS.size == VolkvnBuiltinProxies.definitions.size) {
            "builtin GUID count must match definitions"
        }
    }

    fun stableGuidOrder(): List<String> = STABLE_GUIDS.toList()

    /** First four helpers — prioritized when the public pool has no URL-test success. */
    fun whitelistOnlyStableGuids(): Set<String> =
        VolkvnBuiltinProxies.definitions.mapIndexedNotNull { index, def ->
            if (def.useInWhitelistOnlyPool) STABLE_GUIDS[index] else null
        }.toSet()

    /**
     * Public pool GUIDs plus built-in helpers (unique, builtins first in stable order).
     */
    fun mergePublicAndBuiltinGuids(): MutableList<String> {
        val out = LinkedHashSet<String>()
        STABLE_GUIDS.forEach { out.add(it) }
        MmkvManager.decodeServerList(AppConfig.VOLKVN_SUBSCRIPTION_ID).forEach { out.add(it) }
        return out.toMutableList()
    }

    fun ensureBuiltinHelpers(context: Context) {
        val item = SubscriptionItem(
            remarks = context.getString(R.string.volkvn_builtin_helpers_remarks),
            url = "",
            enabled = true,
            autoUpdate = false,
        )
        MmkvManager.encodeSubscription(AppConfig.VOLKVN_BUILTIN_HELPERS_SUBSCRIPTION_ID, item)

        VolkvnBuiltinProxies.definitions.forEachIndexed { index, def ->
            val guid = STABLE_GUIDS[index]
            val profile = buildProfile(def)
            val existing = MmkvManager.decodeServerConfig(guid)
            if (existing == null || !profile.contentEqualsForBuiltin(existing)) {
                MmkvManager.encodeServerConfig(guid, profile)
            }
        }
    }

    private fun buildProfile(def: VolkvnBuiltinProxies.BuiltinTrojanDef): ProfileItem {
        val p = ProfileItem.create(EConfigType.TROJAN)
        p.subscriptionId = AppConfig.VOLKVN_BUILTIN_HELPERS_SUBSCRIPTION_ID
        p.remarks = def.profileName
        p.server = def.address
        p.serverPort = def.port.toString()
        p.password = VolkvnBuiltinProxies.SHARED_PASSWORD
        p.network = NetworkType.TCP.type
        p.security = AppConfig.TLS
        p.sni = def.sni
        p.alpn = def.alpn
        p.fingerPrint = "qq"
        p.insecure = false
        return p
    }

    private fun ProfileItem.contentEqualsForBuiltin(other: ProfileItem): Boolean =
        configType == other.configType &&
            subscriptionId == other.subscriptionId &&
            remarks == other.remarks &&
            server == other.server &&
            serverPort == other.serverPort &&
            password == other.password &&
            network == other.network &&
            security == other.security &&
            sni == other.sni &&
            alpn == other.alpn &&
            fingerPrint == other.fingerPrint &&
            (insecure ?: false) == (other.insecure ?: false)
}
