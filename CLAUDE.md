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

`./gradlew test` runs JVM unit tests. They are scoped to the pure string surgery in
`data/template/` (CSS scanner + legacy-WebView decoration shim) — the code that rewrites
every template on every box, whose branches don't show up in an on-device screenshot.
Nothing else has tests; there is no instrumented (`androidTest`) source set.

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
├── App.kt                        HiltAndroidApp; enqueues ScheduleSyncWorker + starts MetricsReporter on boot
├── MainActivity.kt               @AndroidEntryPoint thin shell; hosts NavHost + WiFi-setup overlay on top
├── admin/
│   ├── CymaAdminReceiver.kt      DeviceAdminReceiver; provisioned via `dpm set-device-owner`
│   └── DeviceOwnerManager.kt     device-owner checks + silent runtime-permission self-grant
├── wifi/
│   ├── WifiProvisioningCoordinator.kt  app-scoped state machine; watches connectivity, runs setup in background
│   ├── ConnectivityMonitor.kt    validated-internet snapshot + flow + awaitValidatedInternet(timeout)
│   ├── WifiScanner.kt            startScan → List<ScannedNetwork> (run BEFORE the hotspot)
│   │                             + scanAccessPoints() → per-BSSID list for trilateration
│   ├── SoftApController.kt       LocalOnlyHotspot wrapper → SoftApResult (creds or a failure reason)
│   ├── CaptivePortalServer.kt    NanoHTTPD form on the hotspot; SSID dropdown + password + rescan; intercepts OS probes
│   ├── WifiJoiner.kt             DO addNetwork/enableNetwork/reconnect (+ suggestion fallback) → await internet
│   └── HotspotAddress.kt         resolves the hotspot gateway IP for the on-screen fallback URL
├── di/
│   ├── NetworkModule.kt          OkHttp + Retrofit + CymaApi; reads API_BASE_URL from BuildConfig
│   │                             + @Metrics Retrofit (METRICS_BASE_URL) + MetricsApi
│   ├── Metrics.kt                @Qualifier for the metrics-host Retrofit
│   └── StorageModule.kt          DataStore<Preferences> singleton
├── data/
│   ├── api/CymaApi.kt            Retrofit interface + DTOs (schedule, pair, device status)
│   ├── api/MetricsApi.kt         send2 POST + Google geolocate (@Url); DeviceMetricsDto
│   ├── identity/DeviceIdentityRepository.kt   device ID + pairing code via DataStore
│   ├── metrics/
│   │   ├── MetricsReporter.kt    app-scoped 5-min POST loop; started from App.onCreate
│   │   ├── MetricsCollector.kt   reads temp/disk/RAM/CPU/WiFi/IP; every metric nullable
│   │   └── GeoLocationRepository.kt  boot-only WiFi trilateration; fix cached in DataStore
│   ├── schedule/
│   │   ├── ScheduleStore.kt      DataStore wrapper; holds current Schedule JSON; ships default hardcoded schedule
│   │   └── ScheduleRepository.kt  exposes schedule() Flow; syncFromNetwork(); mints the
│   │                              template id = "template-" + sha256(rawTemplate + conteudoJson)
│   ├── media/
│   │   ├── MediaDownloader.kt    OkHttp streaming download; atomic .part→final rename; ETag-aware
│   │   ├── MediaCatalog.kt       url → localFile mapping (sha256(url).<ext> under filesDir/media/)
│   │   └── MediaCacheRepository.kt  materialize(item): Flow<MaterializeResult>; prefetchAll; evictOrphans
│   └── template/
│       ├── TemplateRenderer.kt   placeholder substitution + sanitization + injected compat <style>
│       ├── TemplateCatalog.kt    disk layout filesDir/templates/<id>/index.html + assets/template-N/
│       ├── TemplateAssetExtractor.kt  pulls assets/template-N/... refs out of raw HTML → S3 URLs
│       ├── CssRuleScanner.kt     brace-depth CSS scanner (pure); rules, selectors, declarations
│       └── LegacyDecorationShim.kt  Chromium-52 coloured-decoration shim (pure); see § Templates
├── domain/model/
│   ├── PlaylistItem.kt           sealed interface Video | Image | Template; @Serializable with @SerialName
│   ├── Schedule.kt               Schedule + ActiveWindow; @Serializable
│   └── DeviceState.kt            Unpaired | Paired
├── util/
│   ├── HashUtils.kt              sha256()
│   ├── QrCode.kt                 ZXing QR bitmap + WIFI: payload builder
│   └── WebViewEngine.kt          reads/logs the box's Chromium version (diagnosis only)
├── ui/
│   ├── provisioning/
│   │   ├── WifiSetupOverlay.kt   corner overlay container; dispatches WifiProvisioningCoordinator.state
│   │   ├── WifiJoinCard.kt       step 1: WIFI: join QR + SSID/password banner fallback
│   │   ├── PortalAccessCard.kt   step 2: portal-URL QR (+ typed URL fallback)
│   │   └── ProvisioningCommon.kt shared card/QrTile/NetworkBanner/StatusRow + compact sizes
│   ├── playback/
│   │   ├── PlaybackViewModel.kt  @HiltViewModel; collects schedule → materializes each item → emits PlaybackUiState
│   │   ├── PlaybackScreen.kt     observes ViewModel; shows DownloadDialog or PlaybackEngine or ErrorScreen
│   │   └── PlaybackEngine.kt     queue walker: VideoSlot (ExoPlayer) | ImageSlot (Coil + timer)
│   │                             | TemplateSlot (WebView + reveal cover)
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
7. `PlaybackEngine` walks the list: one engine-owned `ExoPlayer` plays every video item; images use Coil + a `delay()` timer. `REPEAT_MODE_ONE` is set when the playlist has exactly one video item.

### Key invariants

- **Queue swap happens between items**, never mid-item. When the schedule updates, `collectLatest` in the ViewModel cancels the in-progress `loadSchedule` call and re-runs — but the engine only advances to the next item on a natural boundary.
- **Never block on network** — if media isn't cached and the download fails, fall back to streaming so the screen is never blank.
- **One ExoPlayer for the whole `PlaybackEngine`, never one per `VideoSlot`.**
  `PlaybackEngine` builds it (via `buildVideoOnlyPlayer`) and is the only place
  that may `release()` it; `VideoSlot` binds a listener + media item and calls
  `stop()` on dispose. It used to be per-slot, and on a 1 video + 1 image
  playlist (a 28 s loop) that create/release churn tore down the OMX component,
  unloaded/reloaded the codec `.so`, redid 15+ ion allocations of 3.1 MB, and
  dumped ~15 MB of large-object garbage per cycle → recurring 100–490 ms GC
  pauses, plus a `Handler on a dead thread` warning from the frame callback
  racing the released player. Measured on an Allwinner H3: hoisting it took the
  app from 118.9% to 37.6% of a core averaged over the loop. `VideoSlot` also
  owns the `Surface` it wraps around the `TextureView`'s `SurfaceTexture` and
  **must release it** in `onSurfaceTextureDestroyed` — with a persistent player
  nothing else will.
- **No audio renderers — `buildVideoOnlyPlayer` overrides
  `DefaultRenderersFactory.buildAudioRenderers` to add nothing.** On these
  Allwinner boxes the framework's `AudioTrackThread` spins in userspace instead
  of sleeping: `simpleperf` put it at ~96% of one core for the whole duration of
  every video (63% of the app's total CPU), all inside
  `AudioTrack::processAudioBuffer`, burnt on `clock_gettime`/`systemTime` and on
  64-bit divides ARM32 emulates in software (`__divdi3` alone 16% of the thread);
  it sampled as state `R` in 20/20 samples. Muting or zeroing the volume does
  **not** help — a muted `AudioTrack` runs the same loop; only having no audio
  renderer avoids creating one. Playback is video-only by product decision. If a
  schedule ever needs sound, re-measure: the content is 48 kHz AAC while the HAL
  runs at 44.1 kHz, but matching the rate is **not** proven to stop the spin.
- **Cache key = `sha256(sourceUrl)`** — changing a video's URL produces a new cache entry; the old one is cleaned up by `evictOrphans` after the next schedule sync.

### Adding a new feature

- **New API endpoint** → add to `CymaApi` + DTO, call from the relevant repository.
- **New media type** → add variant to `PlaylistItem` sealed interface (remember `@SerialName`), add `ResolvedItem` variant, handle in `PlaybackEngine`.
- **Schedule polling (Phase 3)** → implement `ScheduleRepository.syncFromNetwork()` (stub is already there); add a foreground-loop in `PlaybackViewModel` using `scheduleRepository.schedule().collectLatest + delay(pollIntervalSec)`.
- **Pairing (Phase 2)** → route to `PairingScreen` from `MainActivity` when `DeviceIdentityRepository.getAuthToken() == null`; complete `CymaApi.pair()` + `CymaApi.getDeviceStatus()` polling.

### API base URL

Defined per build type in `app/build.gradle.kts` as `buildConfigField("String", "API_BASE_URL", ...)`. Change both `debug` and `release` when pointing at a new backend. `METRICS_BASE_URL` works the same way (the metrics host is a *different* backend from the playlist API) but its committed default can be overridden from `.env` — see below.

### Secrets and overrides — `.env`

`.env` at the repo root (gitignored; `.env.example` is the committed template) is read
at configure time by `app/build.gradle.kts` into `buildConfigField`s, the same idea as
the pre-existing `keystore.properties`. Two distinct uses, don't conflate them:

- **Secrets** — must not be committed, so there is no default. `GEOLOCATION_API_KEY`.
  Absent → `""`, a Gradle warning, and the dependent feature degrades (metrics still
  report, with null coordinates). A fresh clone still builds.
- **Overrides** — not secret, so the *production value stays committed* and `.env` only
  overrides it, e.g. `METRICS_BASE_URL` for a staging host:
  `secret("METRICS_BASE_URL").ifEmpty { "https://metrics.cyma.digital/" }`. Clone-and-build
  keeps working; nobody needs a secret injected into CI to get a functioning APK.

Don't move a plain URL into the secret category — it buys no security (see below) and
breaks fresh clones.

**A key in `.env` still ships inside the APK.** `BuildConfig` strings are trivially
recoverable from a built APK, so `.env` protects against leaking into git history,
not against extraction from a device. Restrict such keys server-side — for
`GEOLOCATION_API_KEY`, lock it to the Geolocation API with a daily quota cap in Google
Cloud Console. Android package/signature restrictions do **not** apply here: this is a
plain REST call, not the Maps SDK.

## Device metrics

`MetricsReporter` (app-scoped `@Singleton`, own scope, idempotent `start()` from
`App.onCreate`) POSTs a device-health payload to `METRICS_BASE_URL` + `send2` **every
5 minutes**, and is the Android port of the Raspberry-Pi fleet's
`metrics-sender/upload_stats.py`. The payload keys mirror the Pi's byte-for-byte —
including the lone snake_case `location_timestamp` and the values the Pi formats as
`"{:.2f}"` **strings** rather than numbers — so both fleets land in one dashboard with
no backend change. Keep it that way when touching `DeviceMetricsDto`.

An in-app loop, **not** a `WorkManager` worker: WorkManager's periodic floor is 15 min
and can't hit the 5-min cadence, and this is a kiosk app that is always foreground.
Fire-and-forget — a failed POST is logged and retried on the next tick (like the Pi's
`try/except`), and nothing here touches UI state, so playback is never affected. Each
tick first waits up to 60 s for validated internet, so a cold boot's ~17 s association
delay doesn't cost the first datapoint.

**Every metric is nullable and read defensively.** Signage ROMs differ in what they
expose, so an unreadable source degrades to `null` — never a fabricated `0`, which
would be indistinguishable from a real reading on the dashboard. Notes from the boxes:

- `cpuTemp` — sysfs `/sys/class/thermal/thermal_zone*/temp`, falling back to
  `HardwarePropertiesManager` (API 24+, device-owner only — the app is one). Kernels
  report milli-°C, but some BSPs report °C already (a TX-class box at API 24 reports
  `81`), hence the `raw > 1000` divide plus a 1–150 °C plausibility filter.
- `memAvail` is **free disk MB**, not memory — the Pi's confusing name, kept for
  compatibility. `uptime` is in **hours**.
- `cpuUsagePercent` — two `/proc/stat` samples 1 s apart. Readable on our boxes;
  restricted on some hardened ROMs → `null`.
- `wifiSignalStrength` is already dBm from `WifiManager` — no %→dBm conversion, unlike
  the Pi's `nmcli` path.
- `ipAddress` enumerates `NetworkInterface` (works on every API level we support)
  rather than `ConnectivityManager.activeNetwork` (API 23+).

**Location is boot-only**, matching the Pi's one-shot `geolocation.service` (a port of
`geolocation.py`): `GeoLocationRepository.resolveOnBoot()` runs **once per process**,
guarded by an `AtomicBoolean` that is set regardless of outcome, and the fix is cached
in DataStore forever after. A box that fails every attempt keeps reporting the previous
boot's coordinates (or nulls) and does not retry until it reboots — the same "recovery
is a reboot" contract as WiFi provisioning. This bounds Google Geolocation spend at **≤3
calls per boot** (normally 1); an earlier 24-h-TTL design that re-checked each tick
would have burned ~3 calls every 5 min on any box Google can't locate. Retry ladder is
the Pi's: 3 attempts, 10 s → 20 s → 40 s.

Key invariants:
- **Trilateration needs per-BSSID data** — `ScannedNetwork` collapses an SSID with
  several APs into one entry, so `WifiScanner.scanAccessPoints()` exists alongside
  `scan()` and returns one `ScannedAccessPoint` per BSSID (Google needs ≥2 with
  `considerIp = false`).
- **The resolve waits for validated internet before scanning** — which implies the setup
  hotspot is already down, so it can never fight `WifiProvisioningCoordinator` for a
  single-radio box's antenna. Don't move the scan earlier.
- **The 5-min tick never spends a Google call** — it reads the cached fix only.

## Templates (WebView rendering)

A schedule item can be an HTML template: raw HTML authored server-side for modern
browsers, plus a `conteudo`/`campos` payload. The app renders it locally and plays it in a
WebView like any other playlist item.

1. `ScheduleRepository.toTemplateItem` mints `id = "template-" + sha256(rawTemplate + conteudoJson)`.
2. `TemplateAssetExtractor` pulls `assets/template-N/...` refs out of the raw HTML and maps
   them to the S3 bucket `cymadisplay.assets`.
3. `MediaCacheRepository.materializeTemplate` downloads those assets, reads each downloaded
   `.css` to discover second-level assets (fonts), renders, and writes `index.html`
   atomically (`.part` → rename) under `filesDir/templates/<id>/`.
4. `TemplateRenderer.render` substitutes `<!-- name -->` placeholders, strips `on*` handlers,
   sanitizes `<link>`s, rewrites S3 URLs, and injects compat `<style>` blocks.
5. `PlaybackEngine.buildTemplateView` serves the directory over `https://appassets.cyma.local`
   via `WebViewAssetLoader`, with **JavaScript disabled** and `setNetworkAvailable(false)`,
   and reveals the WebView only after `postVisualStateCallback` + a settle delay.

### Legacy WebView CSS support floor

**The floor is Chromium 52** — `com.android.webview` 52.0.2743.100, `/system/app/webview`,
on the API 24 box `1546794507d45000007a`, which reports `ro.build.version.release=16.0`.
**The Android version string on these boxes is worthless.** Read the WebView package
version instead; `WebViewEngine.logOnce` logs it under the `TplWebView` tag at the first
template, next to the engine's own console complaints:

```
adb shell dumpsys package com.android.webview | grep versionName   # also com.google.android.webview
adb logcat -s TplWebView                                           # "WebView engine: chromium=52 ..."
```

Anything newer than Chrome 52 must be shimmed or avoided — `filter` (53), `clip-path` (55),
`position: sticky` (56), CSS Grid (57), `text-decoration-color`/`-line`/`-thickness` (57),
flexbox `gap` (84), `aspect-ratio` (88 — already shimmed by the injected viewport override).

- **Unsupported declarations are dropped silently at parse time** and fall back to the
  initial/inherited value. There is no error for it. That is how template 7's `#a5151c`
  heading underline became white: `text-decoration-color` vanished, so the line painted in
  `currentColor`, which the same rule set to `#fff`.
- **`-webkit-` prefixes do not help.** Verified on hardware: `-webkit-text-decoration-color`
  is just as dead in this build. Never "fix" a gap by adding a prefix without checking on a box.
- **On this engine a native text decoration can never be a different colour from its text.**
  Measured with a sentinel-colour probe page in `org.chromium.webview_shell` (same engine):
  the decoration paints in the *descendant's* glyph colour and follows
  `-webkit-text-fill-color` too, so no `color`/`text-fill-color` trick recovers it. The only
  mechanism is to suppress the decoration and paint the line yourself.
- **A shim must be version-invariant** — the same paint on 52 and on a modern engine — or it
  must be gated. Nothing is gated today, deliberately: a box's WebView can be updated
  underneath a cached `index.html`, and the render is not per-device. For the decoration
  stripe, `text-decoration: none !important` is what makes it invariant; omit it and modern
  engines draw two lines. Confirmed by rendering the same `index.html` in desktop Chrome.
- **Never rewrite the downloaded `.css` on disk, and never rewrite bytes in
  `shouldInterceptRequest`.** The on-disk `index.html` staying exactly what the WebView sees
  is the only cheap way to debug the next quirk (`adb pull` it and open it in Chrome).

### How the coloured-decoration shim works

`LegacyDecorationShim` (pure, unit-tested) scans the template's stylesheets plus its inline
`<style>` blocks with `CssRuleScanner`, and for each rule that really does decorate text in
a literal colour it emits, into the injected `<style>`:

```css
div.body h1 { text-decoration: none !important; }              /* suppress the native line */
div.body h1 > span.cyma-legacy-deco {                          /* paint it ourselves      */
  background-image: linear-gradient(#a5151c, #a5151c) !important;
  background-repeat: repeat-x !important;
  background-size: 4px 0.09em !important;
  background-position: 0 90% !important;
}
```

A stripe on an **inline** box is drawn per line fragment and hugs the text, which is exactly
what a native underline does — measured against the native line on hardware: 3 px thick,
within 1 px of the same position, on both fragments of a wrapped two-line heading. A
`border-bottom` on the block marks only the bottom of the whole block and sits 6 px lower,
so it is not used. Block subjects have no inline box of their own, so the shim wraps their
content in `<span class="cyma-legacy-deco">` (`LegacyDecorationShim.wrapContent`) and styles
the wrapper; inline subjects (`u`, `strike`, …) get the stripe directly.

Adding the next gap: a new emitter in `LegacyDecorationShim` plus JVM test cases. Keep it
version-invariant, keep it guarded, keep it pure.

Key invariants:

- **A rule that declares a decoration colour but no decoration line must be left alone.**
  Template 7's `div.body h2` does exactly that (`text-decoration-color` with no line, red
  rule drawn by `border-bottom`, text `#ffc627`). Its colour declaration is inert even on a
  modern engine; shimming it would recolour the subtitle. `u`/`strike` are the opposite case
  — no line in the CSS, but the UA stylesheet gives them one — so the gate is "a line is
  declared **or** the subject is a UA-decorated element".
- **The shim guards then skips.** Non-literal colour (`var()`, `currentColor`), a rule that
  paints its own `background`, `*`/`html`/`body` subjects, pseudo-elements and states, more
  than 32 candidates, a sheet over the rule cap — each yields no shim, i.e. today's
  rendering. A wrongly-coloured line beats a wrecked template.
- **Killing the native decoration and painting the stripe are one decision.** If nothing
  could be wrapped (same-tag nesting like `<li>` in `<li>`, or markup-only content), the
  shim emits nothing rather than a `text-decoration: none` with no stripe behind it.
- **A shim failure degrades to no shim, never to no template.** `render()` throwing surfaces
  as `MaterializeResult.Error`, i.e. a black slot in the loop, so the scan is wrapped in
  `runCatching`.
- **The injected `<style>` blocks must stay last in `<head>`**, after the template's own
  `<link rel="stylesheet">` — that is what makes our `!important` declarations win ties
  against the template's own `!important` rules. `TemplateRenderer.injectStyles` splices by
  index (a `Regex.replaceFirst(String)` replacement would read a `$` in generated CSS as a
  group reference) and falls back to after `<body>`, then after `<html>`, but never to
  index 0 — content before the doctype puts Chromium in quirks mode and reflows everything.
- **Do NOT disable CSS animations.** Some templates drive visibility through their entrance
  animation (a typing/reveal effect whose pre-animation state is hidden), so `animation: none`
  freezes them hidden and the template shows nothing. Load-time paint jank is handled by the
  reveal cover in `PlaybackEngine`.
- **The cache does not self-invalidate.** The id is `sha256(rawTemplate + conteudoJson)`, so a
  renderer change reaches a box only when the customer's content changes — or when the cache
  is wiped by hand. After shipping any renderer change:

  ```bash
  adb shell su -c 'rm -rf /data/data/com.cyma.videoloop/files/templates'
  adb shell am force-stop com.cyma.videoloop && adb shell am start -n com.cyma.videoloop/.MainActivity
  ```

  Assets re-download after a wipe, so do it on a box that has network.
- **The visible text colour often comes from the content, not the stylesheet.** The
  customer-facing editor emits `<font color="#ffffff">` and `style="color: rgb(255,255,255)"`
  inside the headings, and an author `!important` rule outranks a `style=` attribute. When a
  colour looks wrong, read the rendered `index.html`, not just the CSS.

## WiFi provisioning

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
