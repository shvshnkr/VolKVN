# Changelog

## [1.0.5.2-volkvn_fix3_debug] - versionCode 749

### Fixed

- Auto-recovery after network handoff/sleep: fallback health-check in `probeCoreHealth` now runs even when primary URL throws an exception.
- Health-check fallback URL changed to `https://cp.cloudflare.com/generate_204` for networks where Google endpoints are unstable.
- `onUnderlyingNetworkChanged`: recovery now requires 2 consecutive probe failures to avoid unnecessary stop/start on single flaps.
- During handoff-recover, current server is marked unhealthy and reselected before restart to avoid rebooting into the same dead node.
- Auto-select with `vpnUp=false`: selected server health is checked before `keep selection`; dead selection triggers forced re-pick.
- Selector now expands probing to the rest of the pool (tail wave) when too few alive nodes are found in first waves.

### Diagnostics

- Expanded AGENT logs (`H6-H14`) for runtime analysis of manual checks, watchdog/handoff, and server selection.

---

## [1.0.5.2-volkvn_fix2_debug] — versionCode 748

### Добавлено

- В `V2RayVpnService.configurePerAppProxy`: запись в общий debug-лог списка пакетов (allowlist / bypass) и событие **AGENT** `vpn_app_filter` — проще отлаживать «нет коннекта» при per-app режиме.

### Прочее

- Сборки отладки: в `versionName` сохранён суффикс **`_debug`**.

---

## [1.0.5.1-volkvn_fix2_debug] — versionCode 747

### Исправлено

- Обновление пула: не вызывать `pickBestServer` после каждого импорта при выключенном VPN, если выбранный профиль всё ещё в списке — меньше случайной смены узла перед подключением.

### Добавлено

- `VolkvnAgentDebug`: NDJSON-события в общем логе VolKVN (тег `AGENT`).
- `VolkvnDebugLog`: ограничение размера файла (1 MiB) и обрезка хвоста после записи.

### Изменено

- CI: описание GitHub Release собирается из полного сообщения последнего коммита на `main` (и далее дополняется `CHANGELOG.md`).

---

## [1.0.5.1-volkvn_fix2] — versionCode 746

- Улучшено восстановление при смене сети (handoff), автоперезапуск при падении транспорта и сопутствующие правки.

---

## [1.0.5.1-volkvn_fix1] и ранее

См. историю коммитов в Git (`git log`).
