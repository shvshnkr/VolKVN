package com.v2ray.ang.handler

import android.os.SystemClock
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

object BabukServerSelector {

    private const val TAG = "BabukServerSelector"
    private const val PROBE_TIMEOUT_MS = 2500
    private const val MAX_PROBES = 48

    /**
     * TCP connect probe; returns [Long.MAX_VALUE] on failure.
     */
    private fun tcpLatencyMs(host: String?, port: Int): Long {
        if (host.isNullOrBlank() || port <= 0) return Long.MAX_VALUE
        val start = SystemClock.elapsedRealtime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            }
            SystemClock.elapsedRealtime() - start
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Picks a low-latency server among [subscriptionId] pool: top-3 by TCP connect, then random.
     */
    fun pickBestServer(subscriptionId: String) {
        val guids = MmkvManager.decodeServerList(subscriptionId)
        if (guids.isEmpty()) return

        data class Row(val guid: String, val latency: Long, val profile: ProfileItem)

        val rows = ArrayList<Row>(guids.size.coerceAtMost(MAX_PROBES))
        for (guid in guids.take(MAX_PROBES)) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP) {
                continue
            }
            val host = profile.server
            val port = profile.serverPort?.toIntOrNull() ?: continue
            val lat = tcpLatencyMs(host, port)
            rows.add(Row(guid, lat, profile))
        }

        val alive = rows.filter { it.latency < Long.MAX_VALUE }.sortedBy { it.latency }
        // Same logic as stable 1.0.3-babuk: if every TCP probe fails, keep first profile (do not randomize).
        val pick = when {
            alive.size in 1..3 -> alive.random()
            alive.size > 3 -> alive.take(3).random()
            rows.isNotEmpty() -> rows.first()
            else -> return
        }

        MmkvManager.setSelectServer(pick.guid)
        Log.i(TAG, "Selected ${pick.profile.remarks} (${pick.profile.server}:${pick.profile.serverPort}) latency=${pick.latency}ms")
    }

    private fun <T> List<T>.random(): T = this[Random.nextInt(size)]
}
