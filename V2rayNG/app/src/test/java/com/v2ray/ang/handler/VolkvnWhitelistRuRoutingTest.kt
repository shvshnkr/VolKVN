package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RulesetItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolkvnWhitelistRuRoutingTest {

    @Test
    fun detectsRuGeoDirectBypassRule_geositeRu() {
        val item = RulesetItem(
            outboundTag = AppConfig.TAG_DIRECT,
            domain = listOf("geosite:ru"),
            enabled = true,
        )
        assertTrue(VolkvnWhitelistRuRouting.isRuGeoDirectBypassRule(item))
    }

    @Test
    fun detectsRuGeoDirectBypassRule_geoipRu() {
        val item = RulesetItem(
            outboundTag = AppConfig.TAG_DIRECT,
            ip = listOf(AppConfig.GEOIP_RU),
            enabled = true,
        )
        assertTrue(VolkvnWhitelistRuRouting.isRuGeoDirectBypassRule(item))
    }

    @Test
    fun ignoresProxyOutbound() {
        val item = RulesetItem(
            outboundTag = AppConfig.TAG_PROXY,
            domain = listOf("geosite:ru"),
            enabled = true,
        )
        assertFalse(VolkvnWhitelistRuRouting.isRuGeoDirectBypassRule(item))
    }

    @Test
    fun ignoresDisabledRule() {
        val item = RulesetItem(
            outboundTag = AppConfig.TAG_DIRECT,
            domain = listOf("geosite:ru"),
            enabled = false,
        )
        assertFalse(VolkvnWhitelistRuRouting.isRuGeoDirectBypassRule(item))
    }
}
