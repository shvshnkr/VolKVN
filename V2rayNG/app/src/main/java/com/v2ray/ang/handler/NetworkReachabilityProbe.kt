package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Probes open vs whitelist-only network (Google vs dzen/whitelist sources). Ported from Dahusim.
 */
object NetworkReachabilityProbe {

    private const val GOOGLE_PROBE_URL = "http://www.google.com/generate_204"
    private const val DZEN_PROBE_URL = "http://dzen.ru"
    private const val YA_PROBE_URL = "https://ya.ru"
    private val WHITELIST_PROBE_URLS = listOf(
        "https://gitverse.ru/api/repos/bywarm/rser/raw/branch/master/selected.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist.txt",
        "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/Whitelist%20%E2%84%962.txt",
        "https://raw.githubusercontent.com/Mihuil121/vpn-checker-backend-fox/main/checked/RU_Best/ru_white.txt",
        "https://storage.yandexcloud.net/wall-breaker-c0de-a666/config.txt",
    )
    private const val TIMEOUT_MS = 1800
    private const val FAST_TIMEOUT_MS = 1200

    suspend fun probe(context: Context, fast: Boolean = false): NetworkReachability = coroutineScope {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm?.activeNetwork == null) {
            return@coroutineScope NetworkReachability(
                googleReachable = false,
                dzenReachable = false,
                yaReachable = false,
                whitelistSourceReachable = false,
            )
        }
        val timeoutMs = if (fast) FAST_TIMEOUT_MS else TIMEOUT_MS
        val google = async(Dispatchers.IO) { probeUrl(GOOGLE_PROBE_URL, timeoutMs) }
        val dzen = async(Dispatchers.IO) { probeUrl(DZEN_PROBE_URL, timeoutMs) }
        val ya = async(Dispatchers.IO) { probeUrl(YA_PROBE_URL, timeoutMs) }
        val whitelist = async(Dispatchers.IO) { anyWhitelistSourceReachable(timeoutMs, fast) }
        if (google.await()) {
            return@coroutineScope NetworkReachability(
                googleReachable = true,
                dzenReachable = false,
                yaReachable = false,
                whitelistSourceReachable = false,
            )
        }
        NetworkReachability(
            googleReachable = false,
            dzenReachable = dzen.await(),
            yaReachable = ya.await(),
            whitelistSourceReachable = whitelist.await(),
        )
    }

    private suspend fun anyWhitelistSourceReachable(timeoutMs: Int, fast: Boolean): Boolean = coroutineScope {
        val urls = if (fast) WHITELIST_PROBE_URLS.take(2) else WHITELIST_PROBE_URLS
        urls.map { url -> async(Dispatchers.IO) { probeUrl(url, timeoutMs) } }
            .awaitAll()
            .any { it }
    }

    private suspend fun probeUrl(url: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        runCatching {
            connection.connect()
            connection.responseCode in 200..399
        }.getOrElse { false }.also {
            connection.disconnect()
        }
    }
}
