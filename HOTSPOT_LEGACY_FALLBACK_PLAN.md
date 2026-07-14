# Legacy Hotspot Fallback (Android < 8 / spoofed-ROM TV boxes)

**Status:** Ready to implement
**Scope:** `app/src/main/java/com/cyma/videoloop/wifi/SoftApController.kt` + `app/src/main/AndroidManifest.xml`
**Surface:** ~80 lines added/changed across two files; zero changes downstream of `SoftApCredentials`

---

## 1. Problem

On MXQ-PRO (and similar) TV boxes the WiFi-provisioning overlay shows:

> This Android version is too old to host a setup hotspot (needs Android 8+).

That string is emitted at exactly one place — `SoftApController.start()` at
`SoftApController.kt:72-74`, inside the guard `if (Build.VERSION.SDK_INT < O)`.

### Why it fires

The provisioning flow uses `WifiManager.startLocalOnlyHotspot()`, which exists
only on **API 26 (Android 8.0)+**. When `SDK_INT < 26`, the guard short-circuits
and the box can never raise the setup hotspot, so an offline box can never be
provisioned over WiFi.

### The box under test

`adb shell getprop` on the failing MXQ-PRO:

| Property | Value | Meaning |
|---|---|---|
| `ro.build.version.release` | **12.0** | **Spoofed** — a flat-out lie |
| `ro.build.version.sdk` | **24** | **Ground truth → Android 7.0 (Nougat)** |
| `ro.build.fingerprint` | `Allwinner/dolphin_fvd_p1/dolphin-fvd-p1:7.0/NRD91N/20231124083641:eng/test-keys` | Genuine AOSP 7.0, **Allwinner H3** (`sun8iw7p1` / `exdroid` BSP), `eng/test-keys` (userdebug) |
| `ro.product.model` | `TV BOX` | generic |

This is a classic **franken-ROM**: the manufacturer bumped `ro.build.version.release`
to look modern, but the real framework level is API 24. `Build.VERSION.SDK_INT`
reads `ro.build.version.sdk`, so it correctly returns **24**, and the guard fires
legitimately.

### Confirmed runtime facts (read-only adb)

| Check | Result |
|---|---|
| Device owner | **Yes** — proven by `ACCESS_FINE_LOCATION: flags=[ POLICY_FIXED ]` (only settable via `DevicePolicyManager.setPermissionGrantState`). Note: `dpm is-device-owner` is unsupported on Android 7's `dpm` binary, and `dumpsys devicepolicy` is missing on this BSP ("Can't find service"); the POLICY_FIXED flag is the reliable evidence. |
| `CHANGE_WIFI_STATE` | granted ✓ |
| `ACCESS_WIFI_STATE` | granted ✓ |
| `android.hardware.wifi` / `wifi.direct` | present ✓ |
| Location mode | `3` (on) ✓ |
| `ro.tether.denied` | absent (not denied) ✓ |
| `WRITE_SETTINGS` | **not granted**, appop in default state ⚠️ (the one implementation risk — see §5) |
| `wlan0` | UP at `192.168.15.31` (adb-over-wifi rides this); `dumpsys wifi` shows flapping on SSIDs with IP-obtain watchdog timeouts → associated-but-not-validly-online → arms provisioning |

---

## 2. Solution — Option A: legacy tethering hotspot for `SDK_INT < 26`

On Android < 8, use the deprecated-but-still-available
`WifiManager.setWifiApEnabled(WifiConfiguration, boolean)` to raise the classic
tethering AP. **Note:** these methods plus `getWifiApConfiguration` /
`getWifiApState` and the `WIFI_AP_STATE_*` constants/actions are `@hide` and
**stripped from the public android.jar stub** (compileSdk 34 only exposes
`validateSoftApConfiguration`), so they are invoked **reflectively** via a small
`ApReflection` shim, with the action/extra strings and state ints hardcoded.
This is safe at runtime on pre-P (no hidden-API blocklist), and the legacy path
only runs on API < 26 anyway.

Everything downstream of the hotspot bring-up is API-agnostic and already works
on API 24 — **no changes needed** in:

- `WifiProvisioningCoordinator` — calls `start()`/`stop()` generically; consumes `SoftApCredentials` via the existing `Started` branch.
- `CaptivePortalServer` — NanoHTTPD on whatever IP the AP interface gets.
- `HotspotAddress` — already scans for interfaces named `ap`/`softap`/`wlan*`.
- `WifiJoiner.tryLegacyJoin` (`addNetwork`/`enableNetwork`) — already the primary path; works on API 24 for device owners.
- `WifiScanner`, `WifiSetupOverlay`, `QrCode`, `ConnectivityMonitor` — all generic.

API 26+ boxes are **completely untouched** — they keep the OS-chosen-creds path via `startLocalOnlyHotspot`.

---

## 3. Locked decisions

| Decision | Value |
|---|---|
| SSID (API < 26 only) | `CymaDisplay-XXXX`, where `XXXX` = last 4 chars of the **canonical `deviceId`** (`sha256(ANDROID_ID)[0..9]`, via `DeviceIdentityRepository.getOrCreateDeviceId()`) — identical to the ID sent with the pairing code |
| Passphrase | fixed `cyma102030` (WPA2-valid; rendered in the QR **only**, never on-screen). One constant, trivial to change. |
| Bring-up order | `setWifiApConfiguration(config)` **first** (persists WPA2 + normalizes SSID), then `setWifiApEnabled(null, true)` off the stored config. `setWifiApEnabled(config)` alone on this Allwinner BSP broadcasts the quoted SSID verbatim + ignores the PSK → open AP. |
| `WRITE_SETTINGS` | **all three layers** — manifest declaration + in-app `SecurityException` fallback + `appops set` in the warehouse provisioning script |
| API 26+ path | unchanged |

---

## 4. Complete change set

### 4.1 `app/src/main/java/com/cyma/videoloop/wifi/SoftApController.kt` (material change)

**Imports** — add `BroadcastReceiver`, `Intent`, `IntentFilter`, `WifiConfiguration`, `withTimeoutOrNull`, `java.lang.reflect.Method`.

**`isSupported`** — flip to `true` (both paths now supported). Not referenced
anywhere (coordinator only calls `start()`/`stop()`), so this is correctness only.

**`start()`** — restructure the version gate:
```kotlin
suspend fun start(): SoftApResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (!isLocationServicesEnabled()) {
            return SoftApResult.Failed("Turn on Location …")
        }
        return startLocalOnlyHotspot()        // OS-chosen creds
    }
    return startLegacyTetherHotspot()          // our creds
}
```
The location check stays **only** on the API 26+ branch — `setWifiApEnabled` on
API 24 does not consult location, so checking it would cause spurious failures.

**`stop()`** — add pre-O branch:
```kotlin
@Suppress("DEPRECATION")
fun stop() {
    reservation?.let { res ->                  // API 26+ path
        reservation = null
        runCatching { res.close() }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        runCatching { wifiManager.setWifiApEnabled(null, false) }   // legacy teardown
    }
}
```
(The current `stop()` early-returns when `reservation == null`, which is always
true on the legacy path — so it must be restructured, not just appended to.)

**New private suspend `startLegacyTetherHotspot()`** (`@Suppress("DEPRECATION")`):
1. Suffix = last 4 chars of `DeviceIdentityRepository.getOrCreateDeviceId()` (the canonical deviceId; fallback `"0000"`).
2. Build `WifiConfiguration` with `SSID="CymaDisplay-$suffix"`, WPA2 PSK `cyma102030`, and the full WPA2 bitset (`KeyMgmt.WPA_PSK` + `AuthAlgorithm.OPEN` + `PairwiseCipher.CCMP|TKIP` + `GroupCipher.CCMP|TKIP`).
3. **Primary path**: `ApReflection.setWifiApConfiguration(config)` — **persist** the WPA2 config via the tether setter (the one Settings uses; normalizes the SSID → no literal quotes in broadcast, and applies security). Then `tryApEnable(null)` to bring the AP up off the stored config.
4. **Fallback if the setter is missing/rejected**: `tryApEnable(config)` directly (legacy path; on this BSP may broadcast a quoted SSID / come up open).
5. On accept, read back `getWifiApConfiguration` + log `ssid=… wpaPsk=…` (confirms security honored), then `waitForApUp(ssid, passphrase)`.
6. **Deeper fallback** (enable rejected): `tryApEnable(null)` + `readApConfig()` to recover real creds; if read-back blocked, disable + honest failure.
7. All enable-paths fail → `SoftApResult.Failed("… run: adb shell appops set <pkg> WRITE_SETTINGS allow")`.

**New private `tryApEnable(config): Boolean`** — `runCatching { wifiManager.setWifiApEnabled(config, true) }.getOrDefault(false)`.

**New private `readApConfig(): SoftApCredentials?`** — best-effort `getWifiApConfiguration()` (needs `ACCESS_WIFI_STATE` ✓; may throw on ROMs that gate read behind WRITE_SETTINGS → wrapped in `runCatching`).

**New private `waitForApUp(ssid, passphrase)`** — maps `awaitApState(...)` to a `SoftApResult`.

**New private nested `sealed interface ApState { Enabled; Failed }`** — terminal states we wait for.

**New private file-scope `object ApReflection`** — reflective shim (see §2 correction):
`setWifiApConfiguration(wm, config)` (persist), `setWifiApEnabled(wm, config, enable)`, `getWifiApConfiguration(wm)`, `getWifiApState(wm)`,
plus hardcoded `ACTION_AP_STATE_CHANGED`, `EXTRA_AP_STATE`, `STATE_ENABLED (13)`, `STATE_FAILED (14)`.
Methods resolved once via `WifiManager::class.java.getMethod(...)` (cached, `runCatching` → `null` on ROMs that lack them).

**New private `apStateToTerminal(state: Int): ApState?`** — maps the int to `Enabled`/`Failed`/`null`.

**New private suspend `setupSuffix(): String`** — last 4 chars of `DeviceIdentityRepository.getOrCreateDeviceId()`, fallback `"0000"`. (Now suspend because it reads the DataStore-backed ID.)

**New private suspend `awaitApState(timeoutMs): ApState?`**:
- Fast path: read `wifiManager.wifiApState`; return immediately if already ENABLED/FAILED.
- Otherwise suspend via a `BroadcastReceiver` on `WIFI_AP_STATE_CHANGED_ACTION`, mirroring the existing `WifiScanner.awaitFreshScan` pattern (`WifiScanner.kt:72-96`). `EXTRA_WIFI_AP_STATE` → ENABLED/FAILED resume; unregister on completion/cancellation.
- `withTimeoutOrNull(8_000)` → `null` on timeout.
- The AP-state broadcast is sticky on pre-26 releases, so a transition just before registration is still delivered (no race gap).

**Companion** — add `HOTSPOT_PASSPHRASE = "cyma-setup"` and `AP_UP_TIMEOUT_MS = 8_000L`.

### 4.2 `app/src/main/AndroidManifest.xml`

**Add permission** (after `CHANGE_WIFI_STATE`):
```xml
<!-- WRITE_SETTINGS lets the legacy tethering-hotspot path (API < 26) author
     the AP config via WifiManager.setWifiApEnabled. On API 23+ it is gated at
     runtime by an appop; the warehouse provisioning step runs
     `adb shell appops set com.cyma.videoloop WRITE_SETTINGS allow` to enable it.
     Auto-granted on install below API 23. -->
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

**Update the device-admin comment** (the `dpm` block) to:
```
adb shell dpm set-device-owner com.cyma.videoloop/.admin.CymaAdminReceiver
adb shell appops set com.cyma.videoloop WRITE_SETTINGS allow
```

---

## 5. The `WRITE_SETTINGS` risk — handled three ways

`setWifiApEnabled(nonNullConfig, true)` historically needs the **WRITE_SETTINGS**
appop to *change* the AP config, and it is currently ungranted.

1. **Manifest declaration** (§4.2) — the permission must exist in the app's grant set.
2. **In-app fallback** (§4.1 step 4) — if the custom-config call is blocked (`SecurityException`/returns false), retry with `null` config (uses persisted default, no write op) and read the creds back; if read-back is blocked too, fail honestly with the actionable adb command.
3. **Warehouse provisioning script** — one extra line alongside the existing `dpm set-device-owner` step: `adb shell appops set com.cyma.videoloop WRITE_SETTINGS allow`. Makes the primary path deterministic across the fleet.

On this `eng/test-keys` Allwinner BSP, the primary path will very likely succeed
even without #3 — these ROMs are permissive — but #3 makes it fleet-wide reliable.

---

## 6. Why nothing else changes

- **`HotspotAddress.kt:27-31`** already matches `ap`/`softap`/`wlan*`. The Allwinner legacy AP typically reuses `wlan0` (re-IP'd to `192.168.43.1` after the client drops) or spawns `ap0` — both match. (Eyeball `adb shell ip addr` during the first test build; if neither matches, a one-line tweak covers it.)
- **`CaptivePortalServer`** binds port 80 on whatever IP `HotspotAddress` finds; the tethering DHCP gives phones `192.168.43.1` as gateway → captive-portal probe hits `PROBE_PATHS` → same "Sign in to network" UX.
- **`WifiJoiner.tryLegacyJoin`** is already the primary join path and works on API 24 for device owners.
- **`ConnectivityMonitor`** already has the API 21-22 best-effort branch (`ConnectivityMonitor.kt:42`); on API 24 the `NET_CAPABILITY_VALIDATED` path works.

---

## 7. Verification

The Allwinner H3 has **one WiFi radio**: bringing up the AP tears down the `wlan0`
client, so **adb-over-wifi drops while the hotspot is up**. Test accordingly.

1. `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Prefer adb-over-USB for the test session, or reconnect to the `Cyma-Setup-XXXX` AP after it arms.
3. Force the box offline (forget current network / move out of range) so `validatedInternetFlow` arms provisioning.
4. Confirm the overlay shows the **QR + `Cyma-Setup-XXXX` / `cyma-setup`** (not the "too old" error).
5. Scan the QR with a phone → joins hotspot → captive portal auto-opens (or browse `http://192.168.43.1`).
6. Pick network + enter password → box joins, overlay clears.
7. `adb logcat -s SoftApController WifiProvisioning` — expect `legacy hotspot up`; no `SecurityException`. If a WRITE_SETTINGS exception appears, run the `appops set` line and re-test.

### JDK note
If `JdkImageTransform`/`jlink` failures occur during the build, recover with:
```bash
pkill -f GradleDaemon && rm -rf ~/.gradle/caches/*/transforms/ && ./gradlew assembleDebug
```
(JDK 17 is pinned in `gradle.properties` and `.gradle/config.properties`.)

---

## 8. Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/cyma/videoloop/wifi/SoftApController.kt` | restructured `start()`/`stop()`; new `startLegacyTetherHotspot`, `tryApEnable`, `readApConfig`, `waitForApUp`, `awaitApState`, `apStateToTerminal`, `setupSuffix`, `ApState`, **`ApReflection`** (reflective shim for the `@hide` tethering APIs); `isSupported=true`; 2 new companion constants; 6 new imports |
| `app/src/main/AndroidManifest.xml` | add `WRITE_SETTINGS` permission; expand the `dpm` provisioning comment with the `appops set` line |

No other files change. API 26+ code path is byte-for-byte unchanged.
