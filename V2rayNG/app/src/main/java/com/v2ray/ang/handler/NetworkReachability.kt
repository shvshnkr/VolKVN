package com.v2ray.ang.handler

/**
 * Device-side reachability before VPN (Dahusim [NetworkReachability]).
 */
data class NetworkReachability(
    val googleReachable: Boolean,
    val dzenReachable: Boolean,
    val yaReachable: Boolean,
    val whitelistSourceReachable: Boolean,
) {
    val hasInternet: Boolean
        get() = googleReachable || dzenReachable || yaReachable || whitelistSourceReachable

    /** dzen/whitelist lists work but Google does not — RU whitelist-only mobile internet. */
    val whitelistOnly: Boolean
        get() = !googleReachable && (dzenReachable || whitelistSourceReachable)
}
