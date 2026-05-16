package com.v2ray.ang.handler

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SimpleModeStatusStoreTest {

    @Before
    fun setUp() {
        SimpleModeStatusStore.clearActivity()
        SimpleModeStatusStore.vpnTransportActive = null
        SimpleModeStatusStore.coreRunning = null
        SimpleModeStatusStore.progressThrottleMs = 300L
        SimpleModeStatusStore.lastProgressEmitAtMs = 0L
        SimpleModeStatusStore.pendingProgressText = null
        SimpleModeStatusStore.elapsedRealtimeMs = { 0L }
    }

    @After
    fun tearDown() {
        SimpleModeStatusStore.vpnTransportActive = null
        SimpleModeStatusStore.coreRunning = null
        SimpleModeStatusStore.clearActivity()
    }

    @Test
    fun shouldBlockBackgroundActivity_whenVpnAndCoreActive() {
        SimpleModeStatusStore.vpnTransportActive = { true }
        SimpleModeStatusStore.coreRunning = { true }
        assertTrue(SimpleModeStatusStore.shouldBlockBackgroundActivity())
    }

    @Test
    fun shouldNotBlockBackgroundActivity_whenVpnDown() {
        SimpleModeStatusStore.vpnTransportActive = { false }
        SimpleModeStatusStore.coreRunning = { true }
        assertFalse(SimpleModeStatusStore.shouldBlockBackgroundActivity())
    }

    @Test
    fun setProgress_notBlockedWhileVpnCoreActive() {
        SimpleModeStatusStore.vpnTransportActive = { true }
        SimpleModeStatusStore.coreRunning = { true }
        SimpleModeStatusStore.pendingProgressText = "TCP 5/100"
        SimpleModeStatusStore.flushPendingProgress()
        assertEquals("TCP 5/100", SimpleModeStatusStore.status.value)
    }

    @Test
    fun progressThrottle_defersRapidUpdates() {
        var now = 0L
        SimpleModeStatusStore.elapsedRealtimeMs = { now }
        SimpleModeStatusStore.emitProgressTextForTest("1/10", force = true)
        assertEquals("1/10", SimpleModeStatusStore.status.value)
        now = 100L
        SimpleModeStatusStore.emitProgressTextForTest("2/10")
        assertEquals("1/10", SimpleModeStatusStore.status.value)
        assertEquals("2/10", SimpleModeStatusStore.pendingProgressText)
        now = 400L
        SimpleModeStatusStore.emitProgressTextForTest("3/10")
        assertEquals("3/10", SimpleModeStatusStore.status.value)
    }

    @Test
    fun flushPendingProgress_emitsLastDeferredLine() {
        SimpleModeStatusStore.pendingProgressText = "9/10"
        SimpleModeStatusStore.flushPendingProgress()
        assertEquals("9/10", SimpleModeStatusStore.status.value)
        assertNull(SimpleModeStatusStore.pendingProgressText)
    }
}
