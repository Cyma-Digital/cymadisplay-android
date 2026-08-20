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
| `aosp-1.1.0.apk` | `com.anydesk.adcontrol.aosp` | 1.1.0 | `f6d3188c8376` |
| `flauncher-0.18.0.apk` | `me.efesser.flauncher` | 0.18.0 | `376fc528c880` |

`aosp-1.1.0.apk` is AnyDesk's **AdControl** plugin, AOSP variant — not a
standalone app. AnyDesk can capture the screen on its own but cannot inject
touches or keys; the plugin holds `android.permission.INJECT_EVENTS`
(`signature|privileged`) and AnyDesk binds to it. AnyDesk publishes one variant
per vendor (Knox, Lenovo, …); `.aosp` is the generic one, which is why it works
on these test-keys eng ROMs. Without it AnyDesk is view-only. Keep it
version-matched to the AnyDesk client. Runbook step 2.3 has the grant check.

## assets/

| File | Purpose |
|---|---|
| `wallpaper.png` | FLauncher home-screen wallpaper (runbook step 4.1) |

`wallpaper.png` is **solid black** — all 2,073,600 pixels are opaque `#000000`.
A correct install and a failed one therefore look the same on screen; check the
file's owner and SELinux label instead (the runbook's step 4.1 does).

It is also 1920×1080 while the reference panel is 1280×720, so FLauncher
downscales it at every draw. Re-exporting at 1280×720 is the cheaper asset; see
the runbook's content specifications.
