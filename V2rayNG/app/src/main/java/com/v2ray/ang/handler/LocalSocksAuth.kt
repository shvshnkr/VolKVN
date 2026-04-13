package com.v2ray.ang.handler

import com.v2ray.ang.util.Utils
import java.util.concurrent.atomic.AtomicReference

/**
 * Session credentials for local SOCKS/HTTP when bound to **TCP loopback** (hev-tun or LAN proxy sharing).
 * Default VolKVN path uses **Unix sockets in app filesDir** instead (see [V2rayConfigManager]); then no
 * password is needed because other UIDs cannot open that socket path.
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
