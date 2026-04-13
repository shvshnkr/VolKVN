package com.v2ray.ang.handler

import com.v2ray.ang.util.Utils
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-session credentials for the local SOCKS inbound (mitigates unauthenticated loopback SOCKS).
 * Regenerated each time the VPN/proxy service is started.
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
