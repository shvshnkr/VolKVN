package com.v2ray.ang.handler

/**
 * Built-in Trojan helpers for simple-mode auto-selection (ported from Husi WhitelistBuiltinProxies).
 * Same shared password across nodes — intentional community-style bootstrap; not a secret boundary.
 */
object VolkvnBuiltinProxies {

    /** Shared across built-in helpers (same as Husi). */
    const val SHARED_PASSWORD: String = "Qfw0MqoyNkSvqjRhZ_x5WNM3V_tF6q"

    data class BuiltinTrojanDef(
        /** Stable profile name for idempotent sync */
        val profileName: String,
        val address: String,
        val port: Int,
        val sni: String,
        /** Comma-separated ALPN list */
        val alpn: String,
        /** First four are prioritized when the public pool has no successful URL-test */
        val useInWhitelistOnlyPool: Boolean,
    )

    val definitions: List<BuiltinTrojanDef> = listOf(
        BuiltinTrojanDef(
            profileName = "Simple helper PL #41",
            address = "87.239.104.97",
            port = 7443,
            sni = "plthree.rushtaxi.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper PL #42",
            address = "109.120.190.146",
            port = 8443,
            sni = "pl.serverstats.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper PL #43",
            address = "109.120.191.129",
            port = 8443,
            sni = "pltwo.rushtaxi.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper PL #44",
            address = "79.174.95.188",
            port = 7443,
            sni = "pltwo.rushtaxi.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper RU federal",
            address = "ru.federal-usa.com",
            port = 8443,
            sni = "ru.federal-usa.com",
            alpn = "h3,h2,http/1.1",
            useInWhitelistOnlyPool = false,
        ),
    )
}
