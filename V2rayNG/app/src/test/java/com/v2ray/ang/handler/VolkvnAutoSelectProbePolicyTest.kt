package com.v2ray.ang.handler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolkvnAutoSelectProbePolicyTest {

    @Test
    fun suppressRepeatPrepareReasons_withinCooldown_sameHash() {
        val now = 1_000_000L
        val hash = 42L
        assertTrue(
            VolkvnAutoSelectProbePolicy.suppressRepeatPrepareReasons(
                now = now,
                lastPrepareAt = now - 30_000L,
                poolHash = hash,
                lastPreparePoolHash = hash,
            ),
        )
    }

    @Test
    fun suppressRepeatPrepareReasons_afterCooldown() {
        val now = 1_000_000L
        val hash = 42L
        assertFalse(
            VolkvnAutoSelectProbePolicy.suppressRepeatPrepareReasons(
                now = now,
                lastPrepareAt = now - VolkvnAutoSelectProbePolicy.PREPARE_COOLDOWN_MS - 1L,
                poolHash = hash,
                lastPreparePoolHash = hash,
            ),
        )
    }

    @Test
    fun suppressRepeatPrepareReasons_hashChanged() {
        val now = 1_000_000L
        assertFalse(
            VolkvnAutoSelectProbePolicy.suppressRepeatPrepareReasons(
                now = now,
                lastPrepareAt = now - 30_000L,
                poolHash = 42L,
                lastPreparePoolHash = 99L,
            ),
        )
    }

    @Test
    fun computeProxyIdSetHash_stableForSamePool() {
        val guids = listOf("a", "b")
        val h1 = VolkvnAutoSelectProbePolicy.computeProxyIdSetHash(guids)
        val h2 = VolkvnAutoSelectProbePolicy.computeProxyIdSetHash(guids)
        assertTrue(h1 == h2 && h1 != 0L)
    }

    @Test
    fun computeProxyIdSetHash_changesWhenPoolChanges() {
        val h1 = VolkvnAutoSelectProbePolicy.computeProxyIdSetHash(listOf("a", "b"))
        val h2 = VolkvnAutoSelectProbePolicy.computeProxyIdSetHash(listOf("a", "b", "c"))
        assertTrue(h1 != h2)
    }
}
