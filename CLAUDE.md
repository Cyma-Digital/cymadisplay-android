# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android kiosk/signage app (`com.cyma.videoloop`) that downloads a scheduled playlist of videos and images from a backend and plays them on a continuous loop. Locked to landscape, touch-screen optional, no playback controls visible.

## Build / Run

```bash
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease        # minified + shrunk
./gradlew installDebug           # install on connected device/emulator
./gradlew lint
./gradlew clean
```

No test suite exists yet — `./gradlew test` is a no-op.

**Toolchain**: Gradle 8.5 · AGP 8.2.0 · Kotlin 1.9.20 · KSP 1.9.20-1.0.14 · Compose Compiler 1.5.4 · JDK 17 · `compileSdk` 34 · `minSdk` 21.

**JDK pinning (do not break this)**: AGP's `JdkImageTransform` invokes `jlink` from whichever JVM the Gradle daemon is running on. The snap Android Studio's bundled JBR (JDK 21) has a broken `jlink` for that transform, so the daemon **must** run on system JDK 17. Two files pin this — keep both in sync:

- `gradle.properties` → `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` (used by terminal `./gradlew`)
- `.gradle/config.properties` → `java.home=/usr/lib/jvm/java-17-openjdk-amd64` (read by `.idea/gradle.xml`'s `#GRADLE_LOCAL_JAVA_HOME` token; controls the JDK Android Studio launches Gradle with)

If Android Studio rewrites `.gradle/config.properties` back to its bundled JBR, the build will fail again. After any IDE-driven setting change, verify both files still point to JDK 17.

If a build fails with `Execution failed for JdkImageTransform … jlink … finished with non-zero exit value 1`, the recovery is: `pkill -f GradleDaemon && rm -rf ~/.gradle/caches/*/transforms/ && ./gradlew assembleDebug`.

All dependency versions are in `gradle/libs.versions.toml`.

## Architecture

### Package layout

```
com.cyma.videoloop/
├── App.kt                        HiltAndroidApp; enqueues ScheduleSyncWorker on boot
├── MainActivity.kt               @AndroidEntryPoint thin shell; hosts NavHost + WiFi-setup overlay on top
├── admin/
│   ├── CymaAdminReceiver.kt      DeviceAdminReceiver; provisioned via `dpm set-device-owner`
│   └── DeviceOwnerManager.kt     device-owner checks + silent runtime-permission self-grant
├── wifi/
│   ├── WifiProvisioningCoordinator.kt  app-scoped state machine; watches connectivity, runs setup in background
│   ├── ConnectivityMonitor.kt    validated-internet snapshot + flow + awaitValidatedInternet(timeout)
│   ├── WifiScanner.kt            startScan → List<ScannedNetwork> (run BEFORE the hotspot)
│   ├── SoftApController.kt       LocalOnlyHotspot wrapper → SoftApResult (creds or a failure reason)
│   ├── CaptivePortalServer.kt    NanoHTTPD form on the hotspot; SSID dropdown + password + rescan; intercepts OS probes
│   ├── WifiJoiner.kt             DO addNetwork/enableNetwork/reconnect (+ suggestion fallback) → await internet
│   └── HotspotAddress.kt         resolves the hotspot gateway IP for the on-screen fallback URL
├── di/
│   ├── NetworkModule.kt          OkHttp + Retrofit + CymaApi; reads API_BASE_URL from BuildConfig
│   └── StorageModule.kt          DataStore<Preferences> singleton
├── data/
│   ├── api/CymaApi.kt            Retrofit interface + DTOs (schedule, pair, device status)
│   ├── identity/DeviceIdentityRepository.kt   device ID + pairing code via DataStore
│   ├── schedule/
│   │   ├── ScheduleStore.kt      DataStore wrapper; holds current Schedule JSON; ships default hardcoded schedule
│   │   └── ScheduleRepository.kt  exposes schedule() Flow; syncFromNetwork() stub for Phase 3
│   └── media/
│       ├── MediaDownloader.kt    OkHttp streaming download; atomic .part→final rename; ETag-aware
│       ├── MediaCatalog.kt       url → localFile mapping (sha256(url).<ext> under filesDir/media/)
│       └── MediaCacheRepository.kt  materialize(item): Flow<MaterializeResult>; prefetchAll; evictOrphans
├── domain/model/
│   ├── PlaylistItem.kt           sealed interface Video | Image; @Serializable with @SerialName
│   ├── Schedule.kt               Schedule + ActiveWindow; @Serializable
│   └── DeviceState.kt            Unpaired | Paired
├── util/
│   ├── HashUtils.kt              sha256()
│   └── QrCode.kt                 ZXing QR bitmap + WIFI: payload builder
├── ui/
│   ├── provisioning/
│   │   ├── WifiSetupOverlay.kt   corner overlay container; dispatches WifiProvisioningCoordinator.state
│   │   ├── WifiJoinCard.kt       step 1: WIFI: join QR + SSID/password banner fallback
│   │   ├── PortalAccessCard.kt   step 2: portal-URL QR (+ typed URL fallback)
│   │   └── ProvisioningCommon.kt shared card/QrTile/NetworkBanner/StatusRow + compact sizes
│   ├── playback/
│   │   ├── PlaybackViewModel.kt  @HiltViewModel; collects schedule → materializes each item → emits PlaybackUiState
│   │   ├── PlaybackScreen.kt     observes ViewModel; shows DownloadDialog or PlaybackEngine or ErrorScreen
│   │   └── PlaybackEngine.kt     queue walker: VideoSlot (ExoPlayer) | ImageSlot (Coil + LaunchedEffect timer)
│   └── pairing/
│       ├── PairingViewModel.kt   loads deviceId + pairingCode; stub UI for Phase 2
│       └── PairingScreen.kt      displays device ID + 6-char pairing code
└── work/
    ├── ScheduleSyncWorker.kt     @HiltWorker periodic; calls scheduleRepository.syncFromNetwork()
    └── MediaPrefetchWorker.kt    @HiltWorker; prefetchAll + evictOrphans after schedule sync
```

### Data flow

1. `App.onCreate` enqueues `ScheduleSyncWorker` (15-min periodic, network-required).
2. `MainActivity` → `PlaybackScreen` → `PlaybackViewModel`.
3. `PlaybackViewModel.init` collects `scheduleRepository.schedule()` (backed by DataStore; defaults to hardcoded demo video).
4. For each `PlaylistItem`, `MediaCacheRepository.materialize()` emits `Downloading(progress)` while downloading, then `Ready(file)` — skips download if file already exists on disk.
5. On download error, the VM falls back to streaming the remote URL directly so playback is never blocked.
6. Once all items are resolved, `PlaybackUiState.Ready(items)` is emitted to `PlaybackScreen`.
7. `PlaybackEngine` walks the list: videos use a single `ExoPlayer` instance per slot; images use Coil + a `delay()` timer. `REPEAT_MODE_ONE` is set when the playlist has exactly one video item.

### Key invariants

- **Queue swap happens between items**, never mid-item. When the schedule updates, `collectLatest` in the ViewModel cancels the in-progress `loadSchedule` call and re-runs — but the engine only advances to the next item on a natural boundary.
- **Never block on network** — if media isn't cached and the download fails, fall back to streaming so the screen is never blank.
- **One ExoPlayer per `VideoSlot`** — released in `DisposableEffect`. Don't rebuild it on recomposition; key it on `item.uri`.
- **Cache key = `sha256(sourceUrl)`** — changing a video's URL produces a new cache entry; the old one is cleaned up by `evictOrphans` after the next schedule sync.

### Adding a new feature

- **New API endpoint** → add to `CymaApi` + DTO, call from the relevant repository.
- **New media type** → add variant to `PlaylistItem` sealed interface (remember `@SerialName`), add `ResolvedItem` variant, handle in `PlaybackEngine`.
- **Schedule polling (Phase 3)** → implement `ScheduleRepository.syncFromNetwork()` (stub is already there); add a foreground-loop in `PlaybackViewModel` using `scheduleRepository.schedule().collectLatest + delay(pollIntervalSec)`.
- **Pairing (Phase 2)** → route to `PairingScreen` from `MainActivity` when `DeviceIdentityRepository.getAuthToken() == null`; complete `CymaApi.pair()` + `CymaApi.getDeviceStatus()` polling.

### API base URL

Defined per build type in `app/build.gradle.kts` as `buildConfigField("String", "API_BASE_URL", ...)`. Change both `debug` and `release` when pointing at a new backend.

## WiFi provisioning

> **DISABLED ON THIS BRANCH (`no-wifi-provisioning`).** The box must never raise a
> setup hotspot here — its WiFi is configured by hand through the device's own Android
> Settings. The code below is all still present and compiles, but nothing drives it:
> `MainActivity` does not call `coordinator.ensureRunning()` and does not render
> `WifiSetupOverlay`. Those two call sites are the entire switch; restore them (see
> `main`) to re-enable. Everything after this banner describes the behavior **on
> `main`** and stays accurate for the code that's in the tree — don't "fix" the missing
> call. The manifest still declares the WiFi/location permissions; they're unreachable
> with no code path running.

Provisioning runs **in the background and never interrupts playback**, and it is
**boot-only**. `WifiProvisioningCoordinator` (app-scoped `@Singleton`, its own scope)
starts exactly one session from `ensureRunning()` at process start: a 1-min
`GRACE_MS` window in which the WiFi client can associate with an already-configured
network (Android takes a while after a cold boot — measured ~17 s on the TX3), state
held at `Idle` so an online box never flashes setup UI; still offline after it → raise
hotspot + captive portal. `ConnectivityMonitor.validatedInternetFlow()` is watched only
for internet *gained* → tear everything down (`Idle`) and mark provisioning terminal.
**Losing internet later, mid-operation, deliberately does not raise the hotspot** —
recovery from a mid-run outage is a reboot. `MainActivity` calls
`coordinator.ensureRunning()` and renders `WifiSetupOverlay` on top of the
always-running content (`PlaybackScreen`/pairing).

The overlay is **two cards stacked vertically** in a corner (`WifiJoinCard` +
`PortalAccessCard`, shared helpers in `ProvisioningCommon.kt`), in the order they're
performed:
- **Step 1 — join the box's hotspot.** A `WIFI:` join QR (`util/QrCode.wifiQrPayload`,
  parsed natively by Android 10+ camera/Lens and iOS 11+ Camera) **plus** the
  prominent SSID/password banner as the fallback for phones that can't scan `WIFI:`
  payloads. This QR is scannable *before* the phone has any link to the box — only the
  step-2 portal URL depends on the join. The banner must stay legible: on the
  `LocalOnlyHotspot` tier the credentials are OS-random (`AndroidShare_6325`).
- **Step 2 — open the portal.** A QR encoding the full portal URL (so nobody types an
  IP and `:8080`), reachable only once the phone is on the hotspot, opening the
  captive-portal form (`CaptivePortalServer`, NanoHTTPD: SSID dropdown + password +
  rescan). Shows "aguarde o endereço" until the AP interface has an IPv4.

On submit the box tears the hotspot down
(single-radio boxes can't host an AP and be a client at once) and joins via
`WifiJoiner`; once internet validates, the connectivity watcher idles the overlay
automatically. A failed join re-arms the hotspot (and pushes the session deadline
forward, so a few wrong-password retries don't trip the terminal stop below); a
failed hotspot start (e.g. Location services off) surfaces the reason and retries
after 20 s.

**Hotspot bring-up is three-tiered** (`SoftApController.start()`), because only
some mechanisms let the app pick its own SSID/passphrase/gateway:
1. **Tether tier (API 26+, tried first)** — reflection on
   `WifiManager.setWifiApConfiguration` + the hidden `IConnectivityManager.startTethering`
   binder call, the same path the box's own Settings hotspot UI uses. Gets our fixed
   `CymaDisplay-<suffix>` / `cyma102030` credentials and a gateway that's typically
   stable per device (often `192.168.43.1`). Gated behind the `WRITE_SETTINGS` appop
   and per-OEM/per-API reflection support (Android Q's stricter config-write gating
   often blocks it outright) — any failure returns `null` and falls through to tier 2,
   it never surfaces a broken/guessed-credentials hotspot. The config read-back is the
   preferred credential source, but when a ROM hands it back with `preSharedKey`
   null/masked the passphrase we just persisted is substituted — publishing a null
   there would render a `T:nopass` join QR for an AP that is really WPA2, and the phone
   would join-and-fail invisibly.
2. **`LocalOnlyHotspot` (API 26+ fallback)** — the OS chooses SSID, passphrase, and
   gateway subnet, and they can change on every start. This is why some boxes show a
   random SSID like `AndroidShare_6325` instead of `CymaDisplay-*` — expected on ROMs
   where tier 1 doesn't apply (e.g. confirmed on API 29 Amlogic TX3 boxes). The step-1
   join QR is regenerated from the live reservation on every start, so the random creds
   still need no typing; the banner beside it stays the fallback.
3. **Legacy `setWifiApEnabled` reflection (API < 26)** — fixed `CymaDisplay-<suffix>` /
   `cyma102030`, stable BSP gateway. Unaffected by the above; some boxes report a
   higher Android version than they actually run (e.g. an "Android 12" MXQ-PRO that's
   really API 24 and takes this path).

**The join must nudge the framework — `enableNetwork` alone does nothing.**
`addNetwork` + `enableNetwork(netId, disableOthers = true)` only *marks the config
enabled*; the connection is initiated by `WifiConnectivityManager` on its next
connectivity scan, whose 20/40/80 s back-off produced a measured **81 s of dead air**
after a portal submit on the API 29 TX3. `WifiManager.reconnect()` is the call that
would force it, but for apps targeting Q+ it's a documented no-op ("will always fail
and return false") — the device-owner exemption covers
`addNetwork`/`enableNetwork`/`setWifiEnabled`, **not** `reconnect()`. So `WifiJoiner`
escalates nudges instead: `startScan()` → second `startScan()` → a client-radio
off/on bounce (also DO-exempt), waiting for association between each. Association is
the *only* slow step — measured costs once associated: DHCP ~1 s, `NetworkMonitor`
validation ~1 s. `join()` therefore waits on association (polling
`connectionInfo.ssid`) rather than blind-waiting on validation, and returns a
`JoinResult`: `Online` / `AssociatedNoInternet` (dead upstream — don't tell the
installer it's a wrong password) / `NotAssociated` (wrong password or out of range).
Diagnose any future slow join off `WifiJoiner`'s `elapsedMs` logs against those numbers.

**Not achievable without root** (confirmed, don't re-litigate): binding port 80 (so the
portal binds 8080 only — the old port-80-first attempt just logged an `EACCES`
`BindException` every session), and
DNS-based captive-portal auto-popup (no DNS interceptor runs on the hotspot — see
`CaptivePortalServer` kdoc). The portal always runs on 8080 and won't auto-open on
the phone; the step-2 QR (encoding the full portal URL, so no one types `:8080`) is
the deliberate, permanent substitute for both — but it's only reachable *after* the
phone is on the hotspot, which is what the separate step-1 join QR is for.

**The join depends on device-owner status.** A non-privileged app on Android 10+
cannot silently join an arbitrary WiFi network; a device owner can. Provision each
box once at the warehouse (no accounts on the device):

```bash
adb shell dpm set-device-owner com.cyma.videoloop/.admin.CymaAdminReceiver
adb shell appops set com.cyma.videoloop WRITE_SETTINGS allow
# verify:
adb shell dumpsys device_policy | grep -i "Device Owner"
```

Both commands are required on **every** box now, regardless of API level — the
`WRITE_SETTINGS` appop backs both the legacy tier and the API 26+ tether tier, not
just pre-O boxes as before.

Device-owner status also lets `DeviceOwnerManager` self-grant `ACCESS_FINE_LOCATION`
(needed by the scan + hotspot APIs) with no on-device prompt — essential on a
remote-only box. Without device owner the flow degrades: it falls back to a runtime
permission request and the advisory `WifiNetworkSuggestion` API (may not connect).

Key invariants:
- **Scan before hotspot** — a single-radio box can't scan for client networks while
  its AP is up, so `WifiScanner.scan()` runs before `SoftApController.start()` and the
  result is cached for the portal dropdown.
- **Success = validated internet** — `WifiJoiner` only reports success once the box
  actually reaches the internet, so a wrong password or captive AP re-arms the hotspot.
- **Teardown must match what started** — `SoftApController` tracks which of the three
  mechanisms is active and `stop()` tears down exactly that one.
- The captive portal serves **cleartext to the phone** (inbound); `cleartextTrafficPermitted="false"`
  governs only the box app's own outbound traffic, so no `network_security_config` change is needed.
