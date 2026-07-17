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
│   │   └── WifiSetupOverlay.kt   corner QR/status overlay driven by WifiProvisioningCoordinator.state
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

Provisioning runs **in the background and never interrupts playback**.
`WifiProvisioningCoordinator` (app-scoped `@Singleton`, its own scope) watches
`ConnectivityMonitor.validatedInternetFlow()`: internet lost → raise a hotspot +
captive portal and publish `ProvisioningState`; internet gained → tear everything
down (`Idle`). `MainActivity` calls `coordinator.ensureRunning()` and renders
`WifiSetupOverlay` — a corner card — on top of the always-running content
(`PlaybackScreen`/pairing). The card is **two steps, no WiFi-join QR**: step 1 is a
prominent SSID/password banner the installer reads and joins manually on the phone
(there's nothing to scan for this step — the hotspot must be joined before the
portal is reachable at all); step 2 is a single QR encoding the portal URL (or the
URL as text) that opens the captive-portal form (`CaptivePortalServer`, NanoHTTPD:
SSID dropdown + password + rescan). On submit the box tears the hotspot down
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
   it never surfaces a broken/guessed-credentials hotspot.
2. **`LocalOnlyHotspot` (API 26+ fallback)** — the OS chooses SSID, passphrase, and
   gateway subnet, and they can change on every start. This is why some boxes show a
   random SSID like `AndroidShare_6325` instead of `CymaDisplay-*` — expected on ROMs
   where tier 1 doesn't apply (e.g. confirmed on API 29 Amlogic TX3 boxes). Since
   there's no WiFi-join QR, the SSID/password banner on the overlay is the only way
   to join on this tier — it must stay legible and prominent.
3. **Legacy `setWifiApEnabled` reflection (API < 26)** — fixed `CymaDisplay-<suffix>` /
   `cyma102030`, stable BSP gateway. Unaffected by the above; some boxes report a
   higher Android version than they actually run (e.g. an "Android 12" MXQ-PRO that's
   really API 24 and takes this path).

**Not achievable without root** (confirmed, don't re-litigate): binding port 80, and
DNS-based captive-portal auto-popup (no DNS interceptor runs on the hotspot — see
`CaptivePortalServer` kdoc). The portal always runs on 8080 and won't auto-open on
the phone; the QR code (encoding the full portal URL, so no one types `:8080`) is
the deliberate, permanent substitute for both — but it's only reachable *after* the
phone has manually joined the hotspot (there is no join QR).

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
