package com.v2ray.ang.handler

import android.os.SystemClock
import android.util.Log
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object VolkvnServerSelector {

    private const val TAG = "VolkvnServerSelector"
    /** Cellular / DNS can be slow; too short → false negatives. */
    private const val PROBE_TIMEOUT_MS = 4000
    /** First wave: how many profiles to TCP-probe (shuffled order). */
    private const val FIRST_WAVE = 72
    /** Second wave if the first finds no open port (e.g. mobile blocks most endpoints). */
    private const val SECOND_WAVE = 72
    /** Avoid opening too many sockets at once on low-end devices. */
    private const val PARALLEL_PROBES = 20

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

    private fun probeRow(guid: String): Row? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        if (profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP) {
            return null
        }
        val host = profile.server
        val port = profile.serverPort?.toIntOrNull() ?: return null
        val lat = tcpLatencyMs(host, port)
        return Row(guid, lat, profile)
    }

    private data class Row(val guid: String, val latency: Long, val profile: ProfileItem)

    private suspend fun probeGuidsParallel(guids: List<String>): List<Row> = withContext(Dispatchers.IO) {
        if (guids.isEmpty()) return@withContext emptyList()
        val out = ArrayList<Row>(guids.size)
        for (chunk in guids.chunked(PARALLEL_PROBES)) {
            val part = coroutineScope {
                chunk.map { guid ->
                    async(Dispatchers.IO) {
                        probeRow(guid)
                    }
                }.awaitAll().filterNotNull()
            }
            out.addAll(part)
        }
        out
    }

    /**
     * Picks a low-latency server among [subscriptionId] pool:
     * - Shuffles the pool so mobile-reachable nodes are not always missed (previously only the first 48 in list order were tried).
     * - Two waves of TCP probes if the first wave finds nothing reachable.
     * - If no TCP success at all, selects a **random** profile (never the stale “first in MMKV list”).
     */
    suspend fun pickBestServer(subscriptionId: String) {
        val guids = MmkvManager.decodeServerList(subscriptionId)
        if (guids.isEmpty()) return

        val order = guids.shuffled()
        var rows = probeGuidsParallel(order.take(minOf(FIRST_WAVE, order.size)))
        var alive = rows.filter { it.latency < Long.MAX_VALUE }.sortedBy { it.latency }

        if (alive.isEmpty() && order.size > FIRST_WAVE) {
            val rest = order.drop(FIRST_WAVE).take(minOf(SECOND_WAVE, order.size - FIRST_WAVE))
            rows = probeGuidsParallel(rest)
            alive = rows.filter { it.latency < Long.MAX_VALUE }.sortedBy { it.latency }
        }

        val pick = when {
            alive.isEmpty() -> {
                val validGuids = guids.filter { gid ->
                    val p = MmkvManager.decodeServerConfig(gid)
                    p != null && p.configType != EConfigType.CUSTOM && p.configType != EConfigType.POLICYGROUP
                }
                if (validGuids.isEmpty()) return
                val guid = validGuids.random()
                val p = MmkvManager.decodeServerConfig(guid)!!
                Log.w(TAG, "No TCP probe succeeded in sampled waves; fallback random guid=$guid (${p.remarks})")
                Row(guid, Long.MAX_VALUE, p)
            }
            alive.size <= 3 -> alive.random()
            else -> alive.take(3).random()
        }

        MmkvManager.setSelectServer(pick.guid)
        Log.i(
            TAG,
            "Selected ${pick.profile.remarks} (${pick.profile.server}:${pick.profile.serverPort}) " +
                "latency=${if (pick.latency < Long.MAX_VALUE) "${pick.latency}ms" else "n/a (fallback)"}",
        )
    }

    private fun <T> List<T>.random(): T = this[Random.nextInt(size)]
}
