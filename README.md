# VolKVN

## О проекте

Это **форк** Android-клиента **[v2rayNG](https://github.com/2dust/v2rayNG)** (исходный проект под **GNU GPLv3**). База — кодовая база v2rayNG; актуальная сборка этого репозитория указана в `V2rayNG/app/build.gradle.kts` (например, **versionName `1.0.5.0-volkvn`**, **versionCode** см. там же).

Репозиторий ведётся для **обучения и исследования** устройства сетей, VPN, прокси, маршрутизации и смежных механизмов. Материал связан с практикой курса **VibeCode** в среде **Cursor** (vibecode / Cursor).

**Важно:** используйте знания и сборки только в **законных** целях и с соблюдением правил сети, сервисов и применимого законодательства. Публичные списки подписок указывают на сторонние конфигурации — ответственность за соответствие локальным нормам лежит на вас.

---

**English:** This repository is an **educational and research-oriented fork** of [v2rayNG](https://github.com/2dust/v2rayNG) (GPLv3), for studying how network stacks, VPN, and proxy paths behave. It is associated with **VibeCode** coursework using **Cursor**. The fork adds a simplified UI and experiments on top of the upstream app; see `build.gradle.kts` for the exact **versionName** / **versionCode** of this tree.

---

Android client based on [v2rayNG](https://github.com/2dust/v2rayNG) (GPLv3) with a **simple mode**: one switch, public VLESS subscription pools, automatic server pick, and per-app split tunneling toward Telegram, WhatsApp, and YouTube (plus Revanced YouTube when installed).

Application id: `com.volkvn.app` (debug/release). Uninstall any older build signed with a different id before installing.

## Security note (fixed in this fork)

- В этом форке закрыт сценарий, описанный в статье: [Из-за критической уязвимости VLESS клиентов скоро все ваши VPN будут заблокированы](https://habr.com/ru/articles/1020080/).
- Что сделано: локальный SOCKS в цепочке VPN запускается с авторизацией (random user/pass на сессию), и эти же учётные данные прокидываются в `hev-socks5-tunnel` (режим UDP relay `tcp`), чтобы исключить доступ к loopback SOCKS без валидных credentials.
- Это снижает риск обхода per-app split tunnel через прямое подключение spyware к localhost SOCKS-порту.

## Compared to upstream v2rayNG

- **Local SOCKS auth**: each VPN session uses random SOCKS5 username/password on the loopback inbound and matching credentials in `hev-socks5-tunnel` (UDP relay mode `tcp`). This addresses the loopback SOCKS exposure discussed in [this article](https://habr.com/ru/articles/1020080/) (see also [POC](https://github.com/runetfreedom/per-app-split-bypass-poc)).
- **Public pool**: bundled URLs are fetched periodically (WorkManager minimum interval 60 minutes), merged, deduplicated by upstream import logic, then a **TCP connect latency** probe picks among the fastest endpoints (random among top three).
- **Consent gate**: first launch shows risks of unknown servers before any simple UI.
- **Advanced mode**: full v2rayNG `MainActivity` remains available from the simple screen.

## Build

Пошаговая инструкция: **[docs/BUILD.md](docs/BUILD.md)** (Windows — по шагам; Linux/macOS — по аналогии, **не проверялось** в этом репозитории).

Кратко: **JDK 17**, **Android SDK** с `sdk.dir` в `V2rayNG/local.properties`, файл **`libv2ray.aar`** в `V2rayNG/app/libs/` ([AndroidLibXrayLite releases](https://github.com/2dust/AndroidLibXrayLite/releases)), затем из каталога `V2rayNG`: `gradlew.bat assemblePlaystoreDebug` (Windows) или `./gradlew assemblePlaystoreDebug` (Unix).

Release signing is up to you. F-Droid flavor uses `applicationIdSuffix ".fdroid"`.

## CI/CD

- После каждого коммита в `main` автоматически запускается GitHub Actions workflow: сборка + тесты.
- После успешной сборки workflow автоматически обновляет GitHub Release `latest` и прикрепляет APK.
- Ссылка на актуальный релиз: [https://github.com/shvshnkr/VolKVN/releases/latest](https://github.com/shvshnkr/VolKVN/releases/latest)

### Debug logs (send to developer)

USB debugging on, device connected. Examples:

**Linux / macOS (bash):**

```bash
adb logcat --pid=$(adb shell pidof -s com.volkvn.app)
```

**Windows (cmd):**

```bat
adb shell pidof com.volkvn.app
adb logcat --pid=PASTE_PID_HERE
```

Or capture a wide filter:

```bat
adb logcat | findstr /i "volkvn com.volkvn.app GoLog"
```

Copy lines from pressing Connect until the error appears.

## Disclaimer / «Как есть»

**Русский.** Программное обеспечение из этого репозитория предоставляется **«как есть» (as is)**. **Никаких гарантий** не даётся: ни явных, ни подразумеваемых, в том числе пригодности для какой-либо цели, работоспособности, безопасности, отсутствия ошибок или непрерывности работы. Авторы и участники **не несут ответственности** за прямой или косвенный ущерб, потерю данных, нарушение условий третьих сторон или правовые последствия использования. Вы пользуетесь кодом и сборками **на свой страх и риск**. Инструкции, версии зависимостей и сценарии сборки могут устаревать — **проверяйте сами**.

**English.** The software in this repository is provided **“as is”**, **without warranty of any kind**, express or implied, including but not limited to merchantability, fitness for a particular purpose, security, correctness, or uninterrupted operation. **No guarantees.** To the maximum extent permitted by law, the authors and contributors **disclaim liability** for any damages, data loss, third-party claims, or legal consequences arising from use of this project. **Use at your own risk.** Build steps and dependency choices may become outdated — **verify yourself**.

## Legal

- This repository is a **derivative of v2rayNG** and remains under **GNU GPLv3** (see upstream license).
- Public subscription URLs point to **third-party** configurations; you are responsible for compliance with local law and provider terms.

## Distribution

Google Play policy for VPN apps is strict; sideload APK or F-Droid-style distribution is the practical path.
