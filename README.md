# VolKVN

Android client based on [v2rayNG](https://github.com/2dust/v2rayNG) (GPLv3) with a **simple mode** for relatives: one switch, public VLESS subscription pools, automatic server pick, and per-app split tunneling toward Telegram, WhatsApp, and YouTube (plus Revanced YouTube when installed).

Application id: `com.volkvn.app` (debug/release). Uninstall any older build signed with a different id before installing.

## Compared to upstream v2rayNG

- **Local SOCKS auth**: each VPN session uses random SOCKS5 username/password on the loopback inbound and matching credentials in `hev-socks5-tunnel` (UDP relay mode `tcp`). This addresses the loopback SOCKS exposure discussed in [this article](https://habr.com/ru/articles/1020080/) (see also [POC](https://github.com/runetfreedom/per-app-split-bypass-poc)).
- **Public pool**: bundled URLs are fetched periodically (WorkManager minimum interval 60 minutes), merged, deduplicated by upstream import logic, then a **TCP connect latency** probe picks among the fastest endpoints (random among top three).
- **Consent gate**: first launch shows risks of unknown servers before any simple UI.
- **Advanced mode**: full v2rayNG `MainActivity` remains available from the simple screen.

## Build

1. **Android SDK** — full SDK (not only platform-tools): `platforms;android-36`, `build-tools`, **cmdline-tools**, then `sdkmanager --licenses`. Set `sdk.dir` in `V2rayNG/local.properties` (example: `sdk.dir=C:/Android/sdk`).
2. **`libv2ray.aar`** — not stored in git. Download the asset matching [AndroidLibXrayLite releases](https://github.com/2dust/AndroidLibXrayLite/releases) (file `libv2ray.aar`) into `V2rayNG/app/libs/`. Upstream CI uses the tag from the `AndroidLibXrayLite` submodule.
3. **JDK 17** and Gradle: `cd V2rayNG` then:

```bash
./gradlew assemblePlaystoreDebug
```

Release signing is up to you. F-Droid flavor uses `applicationIdSuffix ".fdroid"`.

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

## Legal

- This repository is a **derivative of v2rayNG** and remains under **GNU GPLv3** (see upstream license).
- Public subscription URLs point to **third-party** configurations; you are responsible for compliance with local law and provider terms.

## Distribution

Google Play policy for VPN apps is strict; sideload APK or F-Droid-style distribution is the practical path.
