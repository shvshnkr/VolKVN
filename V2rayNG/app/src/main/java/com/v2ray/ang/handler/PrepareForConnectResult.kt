package com.v2ray.ang.handler

sealed class PrepareForConnectResult {
    data class Success(val guid: String) : PrepareForConnectResult()
    data object NoProfiles : PrepareForConnectResult()
    data object AllProbesDead : PrepareForConnectResult()
}
