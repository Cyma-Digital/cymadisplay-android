# provisioning/

Third-party binaries and assets a warehouse operator needs to bring up a TV box.
Committed on purpose: the boxes are provisioned from a laptop that may not have
network access, and the exact versions below are the ones validated in
[`docs/TV-BOX-SETUP.md`](../docs/TV-BOX-SETUP.md). Do not bump them without
re-running that runbook's acceptance checklist.

`.gitignore` ignores `*.apk` repo-wide; `!provisioning/apks/*.apk` is the
deliberate exception. Nothing built from this repo belongs here — the signage APK
comes out of `app/build/outputs/apk/release/`.

## apks/

| File | Package | Version | sha256 (first 12) |
|---|---|---|---|
| `ad-70000.apk` | `com.anydesk.anydeskandroid` | 7.0.0 | `3d3bb6b374c6` |
| `flauncher-0.18.0.apk` | `me.efesser.flauncher` | 0.18.0 | `376fc528c880` |

**Missing: `aosp-1.1.0.apk`** (`com.anydesk.adcontrol.aosp` 1.1.0), the AnyDesk
input-injection plugin referenced by the runbook's step 2.3. It was not present
in the staging directory these files came from. Without it AnyDesk shows the
screen but cannot inject remote input. Source it from AnyDesk and drop it here.

## assets/

| File | Purpose |
|---|---|
| `wallpaper.png` | FLauncher home-screen wallpaper (runbook step 4.1) |

`wallpaper.png` is 1920×1080, but the reference panel is 1280×720 — FLauncher
downscales it at every draw. Re-exporting at 1280×720 is the cheaper asset; see
the runbook's content specifications.
