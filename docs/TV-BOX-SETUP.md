# TV Box provisioning runbook

Bring-up procedure for a Cyma Display signage box, from a factory box to one
running the playlist unattended.

**Verification status.** Every command marked ✅ was run and confirmed on a real
box in this repo's fleet — an Allwinner H3 (`sun8iw7p1`, `dolphin-fvd-p1`),
Android 7.0 / API 24, 1 GB RAM, 1280×720 panel, eng build with AOSP test keys.
Steps marked ⚠️ are correct in principle but were **not** executed end-to-end,
so validate them on the first box and correct this file.

Order matters. Device owner must be claimed before any account touches the box,
and the launcher preference must be re-set *after* removing the old launcher.

## Run it as a script

`scripts/provision.sh` drives everything below that adb can do, asserting each
step instead of printing output for a human to judge:

```bash
./gradlew assembleRelease
scripts/provision.sh --dry-run     # look first
scripts/provision.sh               # provision the attached box
```

Stages are idempotent, so re-running is always safe. It stops with **exit 10**
at §3 (network) and prints what to do by hand, because there is no adb path to
configure WiFi on API 24; resume with `--from 5`. `--list` shows the stages,
`--only N` runs one, `--serial` picks a box. Read the rest of this file to
understand *why* each step is what it is — the script is the runbook executed,
not a replacement for it. Two things it cannot do at all: AnyDesk's first launch
(§2.4) and fitting the fan (§0).

---

## 0. Prerequisites

- A host with `adb` on the same network, or a USB cable.
- A clone of this repo. The third-party APKs and the wallpaper are committed
  under `provisioning/` — every path below is relative to the repo root, so run
  the commands from there and nothing has to be staged by hand:

  | File | Package | Version |
  |---|---|---|
  | `provisioning/apks/ad-70000.apk` | `com.anydesk.anydeskandroid` | 7.0.0 |
  | `provisioning/apks/aosp-1.1.0.apk` | `com.anydesk.adcontrol.aosp` | 1.1.0 |
  | `provisioning/apks/flauncher-0.18.0.apk` | `me.efesser.flauncher` | 0.18.0 |
  | `provisioning/assets/wallpaper.png` | — | — |

- The signage APK, built from `main`:

  ```bash
  ./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
  ```

- **A fan on the SoC heatsink.** Not optional. Without active cooling these
  boxes sit at 85–93 °C, above the first thermal trip (85 °C), and the kernel
  responds by taking `cpu2`/`cpu3` offline and capping the clock — the box then
  never recovers on its own. With a fan the same workload runs at 61–69 °C with
  all four cores online. ✅ measured both ways.

Connect and confirm one device:

```bash
adb devices          # exactly one entry, state "device"
adb shell getprop ro.build.fingerprint
```

---

## 1. Claim device owner — do this FIRST ✅

Device owner cannot be claimed once an account exists on the box. It is what
lets the app join WiFi silently on Android 10+, self-grant `ACCESS_FINE_LOCATION`
with no on-device prompt, and read the thermal sensor via
`HardwarePropertiesManager`.

```bash
adb shell dpm set-device-owner com.cyma.videoloop/.admin.CymaAdminReceiver
adb shell appops set com.cyma.videoloop WRITE_SETTINGS allow

# verify
adb shell dumpsys device_policy | grep -i "Device Owner"
```

Both commands are required on **every** box regardless of API level —
`WRITE_SETTINGS` backs both the legacy hotspot tier and the API 26+ tether tier.

> The signage APK must already be installed for this to succeed. If you are
> starting from a bare box, run step 2's first install, then come back here.

---

## 2. Install the apps ✅

### 2.1 Signage app

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Always install the release build, and always with `-r`.** A debug↔release swap
has a different signing key, which forces an uninstall — and that **permanently
changes the box's `deviceId` and `pairingCode`**, so the box must be re-paired in
the backend. `install -r` between two release builds preserves identity. ✅
Confirmed: reinstalling release-over-release kept the box paired.

### 2.2 FLauncher

```bash
adb install -r provisioning/apks/flauncher-0.18.0.apk
```

Verified compatible: `sdkVersion:'21'`, ships `armeabi-v7a`, declares a
`leanback-launchable-activity`. Does not become the home screen yet — step 4.

### 2.3 AnyDesk and the AdControl plugin

```bash
adb install -r provisioning/apks/ad-70000.apk       # AnyDesk
adb install -r provisioning/apks/aosp-1.1.0.apk     # AdControl plugin
```

The plugin needs `android.permission.INJECT_EVENTS`, which is
`signature|privileged` — normally ungrantable to an app in `/data/app`. On these
boxes it **is** granted:

```
android.permission.INJECT_EVENTS: granted=true      # ✅ confirmed
```

because the ROM is an eng build signed with the public AOSP test keys
(`...:eng/test-keys`) and the plugin is signed compatibly. **On a box with a
production-signed ROM this will fail**, and AnyDesk will show the screen but
refuse remote input. Check the grant on every new box model:

```bash
adb shell dumpsys package com.anydesk.adcontrol.aosp | grep INJECT_EVENTS
```

If it reports `granted=false`, the plugin has to be pushed to
`/system/priv-app` on a rooted box — a system-partition change, so decide
deliberately. ⚠️ not attempted here.

### 2.4 First launch ⚠️

AnyDesk and the plugin install as `notLaunched=true`. AnyDesk needs one manual
open to generate its ID, and the plugin must be enabled inside AnyDesk's
settings. This needs a remote or mouse on the box; it cannot be completed over
`adb` alone. Record the AnyDesk ID in the fleet inventory.

---

## 3. Configure the network — SSID `cyma`, password `102030`

Three paths. Pick by what the box has.

### Path A — the app's own captive portal (no adb needed)

The designed field flow. On a boot with no validated internet, the app waits a
1-minute grace window, then raises a hotspot plus a captive portal; the
installer joins the hotspot from a phone and submits the target SSID and
password. Enter:

```
SSID:     cyma
Password: 102030
```

**Read this before relying on Path A.** The on-screen setup cards are now
disabled (`SHOW_WIFI_SETUP_UI = false` in `WifiSetupOverlay.kt`) because they
published the hotspot credentials and portal QR to anyone standing in front of
the panel. Provisioning still runs — only the display is silent. That means the
installer must know the hotspot credentials **out of band**:

| Hotspot tier | SSID / password | Usable without the on-screen card? |
|---|---|---|
| Tether (API 26+, preferred) | `CymaDisplay-<suffix>` / `cyma102030` | Yes — fixed credentials |
| Legacy (API < 26) | `CymaDisplay-<suffix>` / `cyma102030` | Yes — fixed credentials |
| `LocalOnlyHotspot` (API 26+ fallback) | OS-random, e.g. `AndroidShare_6325` | **No** |

On a box that lands on the `LocalOnlyHotspot` tier there is now no way for the
installer to learn the SSID and password from the panel. Recover them from
logcat over adb, or use Path B:

```bash
adb logcat -d | grep -i -E "SoftAp|hotspot|SSID"
```

⚠️ This gap is a live consequence of hiding the overlay. If field installs hit
it often, the fix is a display that shows the credentials but not the QR, rather
than re-enabling the full cards.

### Path B — Settings UI over adb ⚠️

Reliable on any API level, but needs a pointer or remote to type the password:

```bash
adb shell am start -a android.settings.WIFI_SETTINGS
```

### Path C — scripted, rooted boxes ⚠️ not verified

`cmd wifi connect-network` is **API 29+ and does not exist on these API 24
boxes** — do not put it in a provisioning script for this hardware. On a rooted
box the remaining option is editing `wpa_supplicant.conf` and restarting the
supplicant. Treat as unvalidated and test on a spare box before fleet use; a
malformed file leaves the box with no network and no remote access.

### Verify, whichever path

```bash
adb shell dumpsys wifi | grep -i -E "SSID|state"
adb logcat -d -s MetricsReporter:D | tail -3     # "metrics posted" == real internet
```

`metrics posted` is the strongest signal — it means the box reached the metrics
backend, not merely associated with the AP.

---

## 4. Make FLauncher the home screen ✅

Order is deliberate: install and prove the new launcher **before** removing the
old one, so the box is never left without a home app.

```bash
# 1. back up the launcher you are about to remove -- it is a user app,
#    so once uninstalled there is no copy left on the box
adb shell pm path com.scmcontrol.premiumnptvlauncher2
adb pull /data/app/com.scmcontrol.premiumnptvlauncher2-1/base.apk \
         premiumnptvlauncher2-BACKUP.apk

# 2. point HOME at FLauncher
adb shell cmd package set-home-activity me.efesser.flauncher/me.efesser.flauncher.MainActivity

# 3. prove it actually starts on this ROM before burning the old one
adb shell am start -n me.efesser.flauncher/me.efesser.flauncher.MainActivity
adb logcat -d | grep -i -E "FATAL|ANR in"          # must be empty

# 4. remove the old launcher
adb shell pm uninstall com.scmcontrol.premiumnptvlauncher2

# 5. RE-SET the preference -- the uninstall clears it
adb shell cmd package set-home-activity me.efesser.flauncher/me.efesser.flauncher.MainActivity

# 6. bring the signage back to the foreground
adb shell monkey -p com.cyma.videoloop -c android.intent.category.LAUNCHER 1
```

Step 5 is not optional. ✅ Observed: after the uninstall, HOME resolved to
`packageName=android` — the "pick a launcher" chooser — because removing the
package dropped the preferred-activity entry. Confirm it stuck, and that it
persisted to disk so it survives a reboot:

```bash
adb shell cmd package resolve-activity -c android.intent.category.HOME \
    -a android.intent.action.MAIN | grep packageName      # -> me.efesser.flauncher
adb shell grep -c flauncher /data/system/users/0/package-restrictions.xml
```

`com.droidlogic.mboxlauncher` is a stock system launcher that stays installed as
a safety net; leave it alone.

### 4.1 Set the FLauncher wallpaper ⚠️ not executed end-to-end

FLauncher's default background is a built-in gradient. The fleet wallpaper is
`provisioning/assets/wallpaper.png`.

**How FLauncher stores it.** FLauncher 0.18.0 is a Flutter app. Its
`WallpaperService` writes the picked image, raw bytes and no re-encode, to
`getApplicationDocumentsDirectory() + "/wallpaper"` — on Android that resolves
through `PathUtils.getDataDirectory()` to `getDir("flutter", MODE_PRIVATE)`:

```
/data/data/me.efesser.flauncher/app_flutter/wallpaper
```

No extension, no companion preference. The gradient stored under the
`gradient_uuid` key in `FlutterSharedPreferences.xml` is only the **fallback**
used when that file is absent, so dropping the file in is the whole
configuration and removing it reverts to the gradient. Determined by reading
strings out of the APK's `lib/*/libapp.so` (`WallpaperService`,
`_wallpaperFile`, `/wallpaper`, `getApplicationDocumentsPath`), **not** by
running it — validate on the first box.

**FLauncher must have been launched at least once** before this path exists:
`app_flutter/` is created by the Flutter engine at startup, not by the
installer. Step 4's item 3 already does that launch.

#### Path A — rooted box, no remote needed (preferred for fleet work)

```bash
adb push provisioning/assets/wallpaper.png /sdcard/wallpaper.png

adb shell su <<'EOS'
set -e
P=/data/data/me.efesser.flauncher
U=$(stat -c %u:%g $P)                  # the dir the installer made; authoritative
mkdir -p $P/app_flutter
cp /sdcard/wallpaper.png $P/app_flutter/wallpaper
chown $U $P/app_flutter $P/app_flutter/wallpaper
chmod 600 $P/app_flutter/wallpaper
restorecon -R $P/app_flutter
EOS

adb shell rm /sdcard/wallpaper.png
adb shell am force-stop me.efesser.flauncher
```

**Feed the script to `su` on stdin, as above — do not write
`adb shell su -c '…multiple lines…'`.** Your local shell strips the quotes, so
the box's `sh` receives a bare `su -c` with its argument on the next line: `su`
prints its usage banner and the rest of the script runs *outside* it. ✅
Reproduced on the reference box (SuperSU 2.82) — `su` dumped usage, `U` came
back empty, and a `chown` with an empty variable would have followed. `adb shell
"su -c '…'"` (quotes nested so the remote shell gets one argument) also works if
you prefer a one-liner. Do not derive the uid by grepping `dumpsys package` for
`userId=` either — the pipe kills the dump mid-write and prints `Failed to write
while dumping service package: Broken pipe`. `pm list packages -U` is not an
option: `Error: Unknown option: -U` on API 24.

`chown` and `restorecon` are both required. A file left owned by `root` or
labelled `u:object_r:sdcard_file:s0` is unreadable by the app, and FLauncher
fails silently back to the gradient rather than erroring — which looks
identical to "the copy didn't happen". Verify:

```bash
adb shell "su -c 'ls -lZ /data/data/me.efesser.flauncher/app_flutter/wallpaper'"
# expect: owner u0_aNN, context u:object_r:app_data_file:s0
```

Then bring the home screen up and look at it:

```bash
adb shell am start -n me.efesser.flauncher/me.efesser.flauncher.MainActivity
adb shell screencap -p /sdcard/home.png && adb pull /sdcard/home.png
adb shell monkey -p com.cyma.videoloop -c android.intent.category.LAUNCHER 1
```

#### Path B — unrooted box, needs a remote or mouse

FLauncher's own picker. Push the file somewhere the picker can reach, then
choose it on the box: **Settings → Wallpaper → pick a local file**.

```bash
adb push provisioning/assets/wallpaper.png /sdcard/Pictures/wallpaper.png
```

FLauncher copies it to the same `app_flutter/wallpaper` path itself, with the
right owner and label, so nothing else is needed. The `/sdcard` copy can be
deleted afterwards.

#### Notes

- **The asset is 1920×1080 and the panel is 1280×720.** FLauncher downscales it
  on every draw of the home screen. That is not on the playback hot path, so it
  costs nothing while the signage is in front — but re-export at 1280×720 (see
  §7) and it costs nothing at all.
- To revert to the stock gradient: delete the file and force-stop.

  ```bash
  adb shell "su -c 'rm /data/data/me.efesser.flauncher/app_flutter/wallpaper'"
  adb shell am force-stop me.efesser.flauncher
  ```

---

## 5. Disable the Google stack

None of this is used by the signage app — it authenticates against the Cyma
backend, gets no push, is not distributed through the Play Store, and resolves
location with its own WiFi trilateration.

```bash
adb shell pm disable-user --user 0 com.android.vending            # Play Store   ✅
adb shell pm disable-user --user 0 com.google.android.gms         # Play Services ✅
adb shell pm disable-user --user 0 com.google.android.youtube.tv  # YouTube       ⚠️

# verify
adb shell pm list packages -d
adb shell 'ps | grep -i -E "gms|vending|finsky|gapps"'            # expect empty
```

Play Store and Play Services were disabled and verified on the reference box.
The YouTube line is ⚠️: the package name `com.google.android.youtube.tv` is
confirmed present in `pm list packages` on this hardware, but the disable itself
was not executed, so confirm it on the first box.

```bash
adb shell pm list packages | grep -i youtube    # confirm the name before disabling
```

Measured effect of disabling Play Store + Play Services ✅:

| | Before | After |
|---|---|---|
| Used PSS | 429 MB | 390 MB |
| Free RAM | 425 MB | 455 MB |
| System CPU | 20.1% of 4 cores | 19.5% |

About **39 MB** of RAM, not the ~91 MB a naive sum of the processes' PSS
suggests — much of that PSS is shared with the zygote and does not come back.
CPU is unchanged, because these processes were sitting idle in RAM rather than
burning cycles. Disable them for the RAM and the background wakeups, not for CPU.

Left enabled on purpose: `com.google.android.gsf` (Google Services Framework).
Disabling it too was not tested; some ROMs tie the Bluetooth remote pairing to
it.

Reverse any of it with:

```bash
adb shell pm enable <package>
```

### Expect Play Store noise right after installing apps

Immediately after step 2 you may see, from PIDs that change every few seconds:

```
W GooglePlayServicesUtil: Google Play services out of date. Requires ... but found ...
I Finsky : ... post-install permissions check for com.anydesk.adcontrol.aosp
```

That is the Play Store running post-install checks queued by *your* installs,
not a crash loop. It stops on its own once the package is disabled and its
services are force-stopped. ✅ diagnosed.

---

## 6. Acceptance checklist

Run all of it before leaving the box.

```bash
# app alive and playing
adb shell pidof com.cyma.videoloop
adb logcat -d -s PlaybackEngine:D | tail -5        # STATE_ENDED ticking

# real internet, not just association
adb logcat -d -s MetricsReporter:D | tail -3       # "metrics posted"

# device owner claimed
adb shell dumpsys device_policy | grep -i "Device Owner"

# thermal headroom -- must be well under 85, cooling state 0
adb shell cat /sys/class/thermal/thermal_zone0/temp
adb shell cat /sys/class/thermal/cooling_device0/cur_state
adb shell cat /sys/devices/system/cpu/online        # expect 0-3

# no audio thread (the perf fix) -- expect 0
PID=$(adb shell pidof com.cyma.videoloop | tr -d '\r')
adb shell "for t in /proc/$PID/task/*; do cat \$t/comm; done" | grep -c AudioTrack

# home screen
adb shell cmd package resolve-activity -c android.intent.category.HOME \
    -a android.intent.action.MAIN | grep packageName

# google stack off
adb shell pm list packages -d
```

Healthy reference figures from a correctly configured box ✅:

| Metric | Expected |
|---|---|
| App CPU, averaged over the playlist loop | ~38% of one core |
| App CPU, while a video plays | ~47% of one core |
| System CPU | ~20% of 4 cores |
| SoC temperature, with fan | 61–69 °C |
| `AudioTrack` threads in the app | 0 |
| `Creating/Releasing ExoPlayer` in logcat | only on schedule changes, never per item |

If app CPU sits above one full core, or an `AudioTrack` thread exists, the box is
running a build from before the audio-renderer fix — reinstall from `main`.

---

## 7. Content specifications

The single largest lever on these boxes is not code, it is the media. The panel
is **1280×720**.

| Asset | Target |
|---|---|
| Video | 1280×720, H.264 High, 3–4 Mbps, 30 fps |
| Image | 1280×720; JPEG unless transparency is needed |

A 1920×1080 source means the decoder handles 2.25× more pixels than the panel
can show and then throws the surplus away in a downscale, with YUV buffers of
3,133,440 bytes each. Bitrate matters even more than resolution: CABAC entropy
decoding scales with the bitstream, and ExoPlayer's loader holds a few seconds
of it in 64 KB chunks, so a high bitrate also drives large-object GC churn.
A 27 Mbps 1080p asset was measured on a sibling box; 5.1 Mbps 1080p on this one.

---

## 8. Rollback

| To undo | Command |
|---|---|
| Google stack | `adb shell pm enable <package>` |
| Launcher | `adb install -r premiumnptvlauncher2-BACKUP.apk` then `set-home-activity` |
| Wallpaper | `adb shell "su -c 'rm /data/data/me.efesser.flauncher/app_flutter/wallpaper'"` then force-stop |
| AnyDesk / plugin | `adb shell pm uninstall com.anydesk.anydeskandroid` (and `...adcontrol.aosp`) |
| WiFi setup cards | `SHOW_WIFI_SETUP_UI = true` in `WifiSetupOverlay.kt`, rebuild |
| Device owner | `adb shell dpm remove-active-admin com.cyma.videoloop/.admin.CymaAdminReceiver` |

Do **not** "roll back" the signage app by installing a debug build over the
release one. It forces an uninstall and resets the box's identity.
