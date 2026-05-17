package com.v2ray.ang.handler

/**
 * When to run a full TCP + URL probe pass before connect (Dahusim AutoServerSelectorProbePolicy).
 */
object VolkvnAutoSelectProbePolicy {

    private const val FULL_PROBE_INTERVAL_MS = 18L * 60 * 60 * 1000
    private const val LAST_KNOWN_GOOD_URL_STALE_MS = 48L * 60 * 60 * 1000
    const val PREPARE_COOLDOWN_MS = 90_000L

    internal fun suppressRepeatPrepareReasons(
        now: Long,
        lastPrepareAt: Long,
        poolHash: Long,
        lastPreparePoolHash: Long,
        cooldownMs: Long = PREPARE_COOLDOWN_MS,
    ): Boolean =
        lastPrepareAt > 0L &&
            now - lastPrepareAt < cooldownMs &&
            lastPreparePoolHash != 0L &&
            poolHash == lastPreparePoolHash

    fun computeProxyIdSetHash(guids: Collection<String>): Long {
        var hash = 1L
        for (id in guids.sorted()) {
            hash = 31L * hash + id.hashCode().toLong()
        }
        return hash
    }

    fun forceFullProbeReason(
        guids: List<String>,
        whitelistBuiltinOnly: Boolean,
        networkHandoff: Boolean = false,
    ): String? {
        val reasons = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val lastProbeAt = MmkvManager.getAutoSelectLastFullProbeAt()
        if (lastProbeAt == 0L || now - lastProbeAt >= FULL_PROBE_INTERVAL_MS) {
            reasons += "interval"
        }
        val hash = computeProxyIdSetHash(guids)
        val storedHash = MmkvManager.getAutoSelectProxyIdSetHash()
        val suppressRepeat = suppressRepeatPrepareReasons(
            now = now,
            lastPrepareAt = MmkvManager.getAutoSelectLastPrepareAt(),
            poolHash = hash,
            lastPreparePoolHash = MmkvManager.getAutoSelectLastPreparePoolHash(),
        )
        if (!networkHandoff) {
            if (!suppressRepeat && storedHash != 0L && hash != storedHash) {
                reasons += "proxy_set_changed"
            }
            if (MmkvManager.wasAutoSelectLastProbeWhitelistOnly() && !whitelistBuiltinOnly) {
                reasons += "wl_to_open"
            }
        }
        val goodId = MmkvManager.getAutoSelectLastKnownGood()
        if (!goodId.isNullOrBlank()) {
            val verifiedAt = MmkvManager.getAutoSelectLastKnownGoodUrlAt()
            val verifiedProfile = MmkvManager.getAutoSelectLastKnownGoodUrlProfile()
            val stale = verifiedAt == 0L ||
                verifiedProfile != goodId ||
                now - verifiedAt >= LAST_KNOWN_GOOD_URL_STALE_MS
            if (!suppressRepeat && stale) {
                reasons += "last_known_good_stale"
            }
        }
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun recordFullProbe(guids: List<String>, whitelistBuiltinOnly: Boolean) {
        MmkvManager.setAutoSelectLastFullProbeAt(System.currentTimeMillis())
        MmkvManager.setAutoSelectProxyIdSetHash(computeProxyIdSetHash(guids))
        MmkvManager.setAutoSelectLastProbeWhitelistOnly(whitelistBuiltinOnly)
    }

    fun recordPostConnectUrlVerified(guid: String) {
        MmkvManager.setAutoSelectLastKnownGoodUrlAt(System.currentTimeMillis())
        MmkvManager.setAutoSelectLastKnownGoodUrlProfile(guid)
    }

    fun recordPrepareCompleted(guids: Collection<String>) {
        val now = System.currentTimeMillis()
        val hash = computeProxyIdSetHash(guids)
        MmkvManager.setAutoSelectLastPrepareAt(now)
        MmkvManager.setAutoSelectLastPreparePoolHash(hash)
    }

    /** After pool import without prepare — keeps hash in sync so the next prepare is not spurious. */
    fun syncProxyIdSetHash(guids: Collection<String>) {
        MmkvManager.setAutoSelectProxyIdSetHash(computeProxyIdSetHash(guids))
    }
}
