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
├── MainActivity.kt               @AndroidEntryPoint thin shell; hosts NavHost
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
├── util/HashUtils.kt             sha256()
├── ui/
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
