package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RulesetItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolkvnRuRoutingPresetTest {

    @Test
    fun whiteRussiaDomainRule_isRuBypass() {
        val item = RulesetItem(
            remarks = "Bypass Russia domains",
            domain = listOf("geosite:category-ru"),
            outboundTag = AppConfig.TAG_DIRECT,
            enabled = true,
        )
        assertTrue(VolkvnWhitelistRuRouting.isRuGeoDirectBypassRule(item))
    }

    @Test
    fun proxyRule_isNotRuBypass() {
        val item = RulesetItem(
            remarks = "proxy",
            domain = listOf("geosite:google"),
            outboundTag = AppConfig.TAG_PROXY,
            enabled = true,
        )
        assertFalse(VolkvnWhitelistRuRouting.isRuGeoDirectBypassRule(item))
    }
}
