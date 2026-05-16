package com.v2ray.ang.handler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReachabilityTest {

    @Test
    fun whitelistOnly_whenGoogleBlockedAndDzenOk() {
        val r = NetworkReachability(
            googleReachable = false,
            dzenReachable = true,
            yaReachable = false,
            whitelistSourceReachable = false,
        )
        assertTrue(r.whitelistOnly)
        assertTrue(r.hasInternet)
    }

    @Test
    fun whitelistOnly_whenGoogleBlockedAndWhitelistSourceOk() {
        val r = NetworkReachability(
            googleReachable = false,
            dzenReachable = false,
            yaReachable = false,
            whitelistSourceReachable = true,
        )
        assertTrue(r.whitelistOnly)
    }

    @Test
    fun notWhitelistOnly_whenGoogleOk() {
        val r = NetworkReachability(
            googleReachable = true,
            dzenReachable = true,
            yaReachable = false,
            whitelistSourceReachable = false,
        )
        assertFalse(r.whitelistOnly)
    }

    @Test
    fun notWhitelistOnly_whenNothingReachable() {
        val r = NetworkReachability(
            googleReachable = false,
            dzenReachable = false,
            yaReachable = false,
            whitelistSourceReachable = false,
        )
        assertFalse(r.whitelistOnly)
        assertFalse(r.hasInternet)
    }
}
