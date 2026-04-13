package com.v2ray.ang.handler

import com.v2ray.ang.util.Utils
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-session credentials for local SOCKS/HTTP inbounds on loopback.
 * Other apps on the device can reach 127.0.0.1 ports; random user/pass prevents use without our process.
 * Regenerated when VPN/proxy starts; cleared in [V2RayServiceManager.stopCoreLoop].
 */
object LocalSocksAuth {
    private val session = AtomicReference<Pair<String, String>?>(null)

    fun regenerate() {
        val u = Utils.getUuid().replace("-", "").take(16)
        val p = Utils.getUuid().replace("-", "")
        session.set(Pair(u, p))
    }

    fun clear() {
        session.set(null)
    }

    fun username(): String = session.get()?.first.orEmpty()

    fun password(): String = session.get()?.second.orEmpty()

    fun isConfigured(): Boolean = session.get() != null
}
