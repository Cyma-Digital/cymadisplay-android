# Repository Guidelines

## Project Overview

Android kiosk/signage app (`com.cyma.videoloop`) that downloads a scheduled playlist of videos and images from a backend and plays them on a continuous loop. Landscape-locked, no visible playback controls.

## Project Structure & Module Organization

Single-module app under `app/`. Source code lives at `app/src/main/java/com/cyma/videoloop/`.

```
├── di/                    Hilt modules (NetworkModule, StorageModule)
├── data/
│   ├── api/               Retrofit interface + DTOs (CymaApi.kt)
│   ├── identity/          Device ID and pairing (DataStore-backed)
│   ├── schedule/          ScheduleStore, ScheduleRepository
│   ├── media/             MediaDownloader, MediaCatalog, MediaCacheRepository
│   └── template/          Template rendering and asset extraction
├── domain/model/          Sealed interfaces: PlaylistItem, Schedule, DeviceState
├── ui/
│   ├── playback/          PlaybackViewModel, PlaybackScreen, PlaybackEngine
│   └── pairing/           PairingViewModel, PairingScreen
├── work/                  ScheduleSyncWorker, MediaPrefetchWorker (WorkManager)
└── util/                  HashUtils
```

Resources are at `app/src/main/res/`. No test directory exists yet.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug     # Debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease   # Minified + shrunk release APK
./gradlew installDebug      # Install on connected device/emulator
./gradlew lint              # Run Android Lint
./gradlew clean             # Clean build outputs
```

No test suite exists — `./gradlew test` is a no-op.

## JDK Requirement (Critical)

**Must use JDK 17.** Pinned in two files — keep both in sync:
- `gradle.properties` → `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64`
- `.gradle/config.properties` → `java.home=/usr/lib/jvm/java-17-openjdk-amd64`

If `JdkImageTransform`/`jlink` failures occur, recover with:
```bash
pkill -f GradleDaemon && rm -rf ~/.gradle/caches/*/transforms/ && ./gradlew assembleDebug
```

## Coding Style & Naming Conventions

- **Language**: Kotlin with Jetpack Compose UI.
- **Style**: `kotlin.code.style=official` (set in `gradle.properties`).
- **Serialization**: Use `@Serializable` with `@SerialName` for all JSON DTOs and sealed class variants.
- **DI**: Hilt — use `@HiltViewModel`, `@AndroidEntryPoint`, `@HiltWorker`; modules go in `di/`.
- **Async**: Kotlin coroutines and `Flow`; use `collectLatest` for reactive schedule observation.
- **Cache keys**: `sha256(sourceUrl)` — see `util/HashUtils.kt`.

## Architecture Conventions

- **Unidirectional data flow**: ViewModel exposes `StateFlow<UiState>`; composables observe and emit events.
- **Queue swaps happen between items**, never mid-item. Schedule updates via `collectLatest` cancel in-progress loads but the engine only advances on natural boundaries.
- **One ExoPlayer per `VideoSlot`**, released in `DisposableEffect`. Key composables on `item.uri`.
- **Never block on network** — fall back to streaming remote URLs if download fails.
- **Dependency versions**: All declared in `gradle/libs.versions.toml` using version catalogs. Do not hardcode versions in `build.gradle.kts`.

## Commit & Pull Request Guidelines

Single-commit history so far. Use concise, imperative-style commit messages describing the intent of the change.

## Toolchain

Gradle 8.5 · AGP 8.2.0 · Kotlin 1.9.20 · KSP 1.9.20-1.0.14 · Compose Compiler 1.5.4 · `compileSdk` 34 · `minSdk` 21 · Target JVM 17.
