package com.v2ray.ang.handler

import com.v2ray.ang.util.Utils
import java.net.URL

/**
 * Fetch subscription bodies on whitelist-only networks via Yandex Translate mirror (Dahusim).
 */
object VolkvnWhitelistSubscriptionFetch {

    private val DIRECT_BS_HOST_SUFFIXES = listOf(
        "gitverse.ru",
        "storage.yandexcloud.net",
    )

    private val MIRROR_HOST_SUFFIXES = listOf(
        "raw.githubusercontent.com",
        "gist.githubusercontent.com",
        "githubusercontent.com",
    )

    private val PROXY_URI_IN_TEXT = Regex(
        """(?i)(vless|vmess|trojan|ss|hysteria2?)://[^\s<"']+""",
    )

    fun shouldUseYandexMirror(
        link: String,
        whitelistRestricted: Boolean,
        vpnConnected: Boolean,
    ): Boolean {
        if (!whitelistRestricted || vpnConnected) return false
        if (link.contains("translate.yandex.", ignoreCase = true)) return false
        val host = linkHost(link) ?: return false
        if (host.isDirectWhitelistHost()) return false
        return host.needsYandexMirror()
    }

    fun yandexTranslateUrl(originalLink: String): String =
        "https://translate.yandex.ru/translate?url=${Utils.encodeURIComponent(originalLink)}&lang=en-ru"

    fun resolveFetchLink(
        link: String,
        whitelistRestricted: Boolean,
        vpnConnected: Boolean,
    ): String = if (shouldUseYandexMirror(link, whitelistRestricted, vpnConnected)) {
        yandexTranslateUrl(link)
    } else {
        link
    }

    fun extractSubscriptionBody(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val looksHtml = trimmed.startsWith("<") ||
            trimmed.contains("<html", ignoreCase = true) ||
            trimmed.contains("<!DOCTYPE", ignoreCase = true)
        if (!looksHtml) {
            return unescapeHtmlEntities(trimmed)
        }
        val unescaped = unescapeHtmlEntities(trimmed)
        val uris = PROXY_URI_IN_TEXT.findAll(unescaped).map { it.value }.distinct().toList()
        if (uris.isNotEmpty()) {
            return uris.joinToString("\n")
        }
        return unescaped
            .replace(Regex("<[^>]+>"), "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun linkHost(link: String): String? = runCatching {
        URL(link).host.lowercase()
    }.getOrNull()

    private fun String.isDirectWhitelistHost(): Boolean =
        DIRECT_BS_HOST_SUFFIXES.any { suffix -> this == suffix || this.endsWith(".$suffix") }

    private fun String.needsYandexMirror(): Boolean =
        MIRROR_HOST_SUFFIXES.any { suffix -> this == suffix || this.endsWith(".$suffix") }

    private fun unescapeHtmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
