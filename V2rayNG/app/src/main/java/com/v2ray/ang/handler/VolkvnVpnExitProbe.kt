package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager.getSocksPort
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Detects VPN exit country via HTTP through local SOCKS (Dahusim VpnExitProbe semantics).
 */
object VolkvnVpnExitProbe {

    private const val TAG = "VolkvnVpnExitProbe"
    private const val MAX_BODY = 4096
    private const val IP_API_JSON = "http://ip-api.com/json/?fields=status,countryCode"

    fun clearCache() {
        MmkvManager.setVpnExitIsRussia(null)
        MmkvManager.setVpnExitProbeProfileId(null)
    }

    /** @return true = RU exit, false = not RU, null = failed */
    fun probeAndStore(profileGuid: String, timeoutMs: Int = 5000): Boolean? {
        if (profileGuid.isBlank()) return null
        val port = getSocksPort()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port))
        parseCountry(fetchBody(IP_API_JSON, proxy, timeoutMs))?.let { code ->
            return storeResult(profileGuid, IP_API_JSON, code)
        }
        VolkvnDebugLog.simpleModeLog("27", "exit_probe_failed guidLen=${profileGuid.length}")
        return null
    }

    private fun fetchBody(url: String, proxy: Proxy, timeoutMs: Int): String? = runCatching {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            conn.inputStream.bufferedReader().readText().take(MAX_BODY)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun parseCountry(body: String?): String? = runCatching {
        val json = JSONObject(body ?: return null)
        if (json.optString("status") != "success") return null
        json.optString("countryCode").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun storeResult(profileGuid: String, via: String, countryCode: String): Boolean {
        val isRu = countryCode.equals("RU", ignoreCase = true)
        MmkvManager.setVpnExitIsRussia(isRu)
        MmkvManager.setVpnExitProbeProfileId(profileGuid)
        VolkvnDebugLog.simpleModeLog("27", "exit_probe via=$via country=$countryCode isRu=$isRu")
        Log.i(TAG, "Exit probe $countryCode isRu=$isRu profile=$profileGuid")
        return isRu
    }
}
