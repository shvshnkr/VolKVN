# Dahusim (fr.husi) → VolKVN (v2rayNG) mapping

| Dahusim | VolKVN | Status |
|---------|--------|--------|
| `NetworkReachabilityProbe.kt` | `handler/NetworkReachabilityProbe.kt` | Ported |
| `DataStore` wl flags | `AppConfig` + `MmkvManager` | Ported |
| `AutoServerSelector.kt` | `VolkvnServerSelector.kt` + `VolkvnAutoSelectProbePolicy.kt` | Extended |
| `AutoServerSelectorProbePolicy.kt` | `VolkvnAutoSelectProbePolicy.kt` | Ported |
| `WhitelistBuiltinProxies` | `VolkvnBuiltinProxies.kt` | Ported |
| `WhitelistBuiltinVlessShareLines` | `VolkvnBuiltinVlessShareLines.kt` | Ported |
| `WhitelistBuiltinBootstrap` | `VolkvnBuiltinBootstrap.kt` | Ported |
| `DefaultUserBootstrap` | `VolkvnDefaultUserBootstrap.kt` | Ported |
| `WhitelistSubscriptionFetch` | `VolkvnWhitelistSubscriptionFetch.kt` | Ported |
| `WhitelistRuRouting` | `VolkvnWhitelistRuRouting.kt` + `V2rayConfigManager` | Ported |
| `VpnExitProbe` | `VolkvnVpnExitProbe.kt` | Ported (SOCKS HTTP) |
| `SimpleHomeScreen` status | `SimpleModeStatusStore` + `SimpleMainActivity.tvStatus` | Ported |
| `BaseService` connect | `VolkvnSimpleModeConnectOrchestrator.kt` | Ported |
| `SimpleModeVpnCoordinator` | Orchestrator + `VolkvnWhitelistNetworkState` | Ported |
| `jdk-matrix-test.yml` (JDK 21–26) | `.github/workflows/jdk-matrix-test.yml` (JDK 19–26, manual) | Ported |
| Room `ProxyEntity` | `ProfileItem` + MMKV | Existing |
