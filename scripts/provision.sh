#!/usr/bin/env bash
# Cyma Display TV-box provisioning — host-side driver for docs/TV-BOX-SETUP.md.
#
# Runs on the laptop, drives one box over adb. Every step asserts its own
# result, so a failure is an exit code rather than a line of output somebody
# has to read and judge. That is the whole point: on these boxes the usual
# failure mode is a command that prints nothing, returns 0, and did nothing.
#
#   scripts/provision.sh                    provision the connected box
#   scripts/provision.sh --serial <id>      pick a box when several are attached
#   scripts/provision.sh --dry-run          print what would run, touch nothing
#   scripts/provision.sh --from 5           resume at a stage (see --list)
#   scripts/provision.sh --only 9           run one stage and stop
#   scripts/provision.sh --list             show stage numbers and exit
#
# Stages are idempotent: a stage that is already satisfied prints SKIP and
# moves on, so re-running after a fix or a reboot is always safe.
#
# EXIT CODES
#   0   provisioned, acceptance checks passed
#   1   a step failed an assertion — read the FAIL line, nothing was guessed
#   10  stopped at the network gate; the box has no internet and no adb path
#       to give it one on API 24. Connect WiFi by hand, then re-run.
#   2   bad usage / preflight problem (no adb, no device, missing APK)
#
# WHAT THIS SCRIPT DELIBERATELY DOES NOT DO
#   - Configure WiFi. `cmd wifi connect-network` is API 29+ and these boxes are
#     API 24; the app's captive portal needs a phone, and Settings needs a
#     mouse. Stage 4 is a hard gate, not an attempt.
#   - Open AnyDesk for its first launch, or enable the AdControl plugin inside
#     AnyDesk's settings. Both need a pointer on the box (runbook §2.4).
#   - Fit the heatsink fan. Without one the box thermal-throttles to two cores
#     and never recovers (runbook §0).

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PKG_APP="com.cyma.videoloop"
PKG_FLAUNCHER="me.efesser.flauncher"
PKG_ANYDESK="com.anydesk.anydeskandroid"
PKG_ADCONTROL="com.anydesk.adcontrol.aosp"
PKG_OLD_LAUNCHER="com.scmcontrol.premiumnptvlauncher2"

FLAUNCHER_ACTIVITY="$PKG_FLAUNCHER/me.efesser.flauncher.MainActivity"
ADMIN_COMPONENT="$PKG_APP/.admin.CymaAdminReceiver"

APK_APP="app/build/outputs/apk/release/app-release.apk"
APK_FLAUNCHER="provisioning/apks/flauncher-0.18.0.apk"
APK_ANYDESK="provisioning/apks/ad-70000.apk"
APK_ADCONTROL="provisioning/apks/aosp-1.1.0.apk"
WALLPAPER="provisioning/assets/wallpaper.png"

# Google packages the signage app never uses: it authenticates against the Cyma
# backend, gets no push, ships outside the Play Store, and locates itself with
# its own WiFi trilateration. Worth ~39 MB of RAM. gsf is left alone on purpose
# — some ROMs tie Bluetooth remote pairing to it.
GOOGLE_PKGS=(com.android.vending com.google.android.gms com.google.android.youtube.tv)

SERIAL=""
DRY_RUN=0
FROM_STAGE=1
ONLY_STAGE=0
STRICT_INJECT=0
KEEP_OLD_LAUNCHER=0
BACKUP_DIR="provisioning/backups"

STAGE_NAMES=(
  "" # 1-indexed
  "preflight            host tools, device, artefacts"
  "install-app          signage APK (must precede device-owner)"
  "device-owner         claim DO + WRITE_SETTINGS appop"
  "install-extras       FLauncher, AnyDesk, AdControl"
  "network-gate         require validated internet  <-- manual step"
  "launcher             back up OEM launcher, make FLauncher HOME"
  "wallpaper            install the FLauncher wallpaper"
  "google-stack         disable Play Store / Services / YouTube"
  "acceptance           final assertions"
)

# ---------------------------------------------------------------- output ----

if [[ -t 1 ]]; then
  C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'
  C_HEAD=$'\033[1;36m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
  C_OK=""; C_WARN=""; C_ERR=""; C_HEAD=""; C_DIM=""; C_OFF=""
fi

stage_no=0
# Not named head(): a function by that name shadows /usr/bin/head for the whole
# script, and several steps pipe into `head -1` — including the pm path read
# that feeds the OEM-launcher backup, immediately before an uninstall.
banner() { echo; echo "${C_HEAD}=== stage $1: ${STAGE_NAMES[$1]%% *} ${C_OFF}"; }
ok()    { echo "  ${C_OK}OK${C_OFF}    $*"; }
skip()  { echo "  ${C_DIM}SKIP${C_OFF}  $*"; }
info()  { echo "  ${C_DIM}·${C_OFF}     $*"; }
warn()  { echo "  ${C_WARN}WARN${C_OFF}  $*" >&2; }
fail()  { echo "  ${C_ERR}FAIL${C_OFF}  $*" >&2; exit 1; }
die()   { echo "${C_ERR}error:${C_OFF} $*" >&2; exit 2; }

# --------------------------------------------------------------- adb glue ----

adbx() {
  if (( DRY_RUN )); then echo "  ${C_DIM}would run:${C_OFF} adb $*"; return 0; fi
  if [[ -n "$SERIAL" ]]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi
}

# adb shell, stdout captured, trailing CR stripped. Android's pty turns every
# \n into \r\n and the \r silently poisons every string comparison downstream.
sh_out() { adbx shell "$@" 2>/dev/null | tr -d '\r'; }

# Root a here-doc script. NEVER write `adb shell su -c '...multiple lines...'`:
# the local shell eats the quotes, the box's sh sees a bare `su -c` with its
# argument on the following line, su prints its usage banner, and the rest of
# the script runs unrooted with empty variables. Reproduced on SuperSU 2.82.
su_script() {
  if (( DRY_RUN )); then
    local body; body="$(cat)"          # drain stdin fully; do not SIGPIPE the caller
    echo "  ${C_DIM}would run (root):${C_OFF} ${body//$'\n'/ ; }"
    return 0
  fi
  if [[ -n "$SERIAL" ]]; then command adb -s "$SERIAL" shell su; else command adb shell su; fi
}

# Grep device output WITHOUT piping adb into grep. `grep -q` exits on its first
# match, the write side gets SIGPIPE, and `set -o pipefail` promotes that to
# exit 141 — a provisioning run that dies for the very reason it succeeded.
# The here-string reads the output fully before grep ever runs.
sh_has()  { local pat="$1"; shift; grep -qi -- "$pat" <<<"$(sh_out "$@")"; }
sh_hasx() { local pat="$1"; shift; grep -qx -- "$pat" <<<"$(sh_out "$@")"; }

pkg_installed() { [[ -n "$(sh_out pm path "$1")" ]]; }

# Wait for a package's process, or for anything, without a bare sleep loop that
# hides how long it actually took.
wait_for() {
  local what="$1" timeout="$2"; shift 2
  local waited=0
  until "$@" >/dev/null 2>&1; do
    (( waited >= timeout )) && return 1
    sleep 2; waited=$(( waited + 2 ))
  done
  (( waited > 0 )) && info "$what after ${waited}s"
  return 0
}

# ------------------------------------------------------------------ args ----

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while (( $# )); do
  case "$1" in
    --serial)   SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    --from)     FROM_STAGE="${2:?--from needs a stage number}"; shift 2 ;;
    --only)     ONLY_STAGE="${2:?--only needs a stage number}"; shift 2 ;;
    --dry-run)  DRY_RUN=1; shift ;;
    --strict-inject) STRICT_INJECT=1; shift ;;
    --keep-old-launcher) KEEP_OLD_LAUNCHER=1; shift ;;
    --list)
      for i in "${!STAGE_NAMES[@]}"; do (( i )) && printf '  %d  %s\n' "$i" "${STAGE_NAMES[$i]}"; done
      exit 0 ;;
    -h|--help)  usage ;;
    *)          die "unknown argument: $1 (try --help)" ;;
  esac
done

[[ "$FROM_STAGE" =~ ^[1-9]$ ]] || die "--from takes a stage number 1-9 (see --list)"
[[ "$ONLY_STAGE" =~ ^[0-9]$ ]]  || die "--only takes a stage number 1-9 (see --list)"

# --only runs exactly one stage, for testing a single step against a box without
# letting the destructive ones (stage 6 uninstalls the OEM launcher) follow on.
run_stage() {
  (( stage_no = $1 ))
  if (( ONLY_STAGE )); then (( stage_no == ONLY_STAGE )); else (( stage_no >= FROM_STAGE )); fi
}

# =========================================================== 1. preflight ====

# Always runs, even under --only/--from: it reads nothing but device state, and
# it is what defines SERIAL, SDK and HAVE_ROOT for every stage after it. Under
# `set -u` a later stage referencing an unset one is a crash, not a warning.
if true; then
  banner 1
  command -v adb >/dev/null || die "adb not on PATH"

  mapfile -t devices < <(command adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ -n "$SERIAL" ]]; then
    [[ " ${devices[*]} " == *" $SERIAL "* ]] || die "serial $SERIAL is not attached and in state 'device'"
  elif (( ${#devices[@]} == 0 )); then
    die "no device in state 'device' (check the cable, or 'adb connect <ip>')"
  elif (( ${#devices[@]} > 1 )); then
    die "${#devices[@]} devices attached — pass --serial (${devices[*]})"
  else
    SERIAL="${devices[0]}"
  fi
  ok "device $SERIAL"

  for f in "$APK_FLAUNCHER" "$APK_ANYDESK" "$APK_ADCONTROL" "$WALLPAPER"; do
    [[ -s "$f" ]] || die "missing or empty: $f"
  done
  ok "provisioning artefacts present"

  if [[ ! -s "$APK_APP" ]]; then
    die "missing $APK_APP — build it first with ./gradlew assembleRelease.
       Never provision with a debug build: the signing key differs, which forces
       an uninstall and permanently changes the box's deviceId and pairingCode."
  fi
  ok "signage APK present ($(du -h "$APK_APP" | cut -f1))"

  SDK="$(sh_out getprop ro.build.version.sdk)"
  FINGERPRINT="$(sh_out getprop ro.build.fingerprint)"
  info "API $SDK — $FINGERPRINT"
  [[ "$SDK" =~ ^[0-9]+$ ]] || fail "could not read ro.build.version.sdk"
  (( SDK >= 21 )) || fail "API $SDK is below the app's minSdk 21"

  # Root is needed only by the wallpaper stage. Detect it now so the run does
  # not get most of the way in and then discover it cannot finish.
  HAVE_ROOT=0
  if [[ "$(sh_out id -u)" == "0" ]]; then
    HAVE_ROOT=1; info "adbd runs as root"
  elif [[ "$(printf 'id -u\n' | su_script 2>/dev/null | tr -d '\r' | tail -1)" == "0" ]]; then
    HAVE_ROOT=1; info "root available via su"
  else
    warn "no root — stage 7 (wallpaper) will be skipped; set it from FLauncher's
        Settings > Wallpaper with a remote instead (runbook §4.1 path B)"
  fi
fi

# ======================================================== 2. install app =====

if run_stage 2; then
  banner 2
  # -r between two release builds preserves the box's identity. A debug/release
  # swap forces an uninstall, which permanently rerolls deviceId and pairingCode
  # and orphans the box in the backend.
  adbx install -r "$APK_APP" >/dev/null || fail "install of $APK_APP failed"
  pkg_installed "$PKG_APP" || fail "$PKG_APP is not installed after a successful install"
  ok "$PKG_APP installed"
fi

# ======================================================= 3. device owner =====

if run_stage 3; then
  banner 3
  if sh_has "Device Owner" dumpsys device_policy; then
    skip "device owner already claimed"
  else
    # Fails outright once any account exists on the box — that is why this runs
    # before anything signs in, and why the failure text matters more than the
    # exit code.
    out="$(adbx shell dpm set-device-owner "$ADMIN_COMPONENT" 2>&1 | tr -d '\r' || true)"
    if ! grep -qi "success" <<<"$out"; then
      fail "dpm set-device-owner failed: ${out:-<no output>}
       Usually means an account already exists on the box. Factory reset and
       claim device owner before anything signs in."
    fi
    ok "device owner claimed"
  fi

  # Backs both the legacy hotspot tier and the API 26+ tether tier — required on
  # every box regardless of API level, not just pre-O ones.
  adbx shell appops set "$PKG_APP" WRITE_SETTINGS allow >/dev/null 2>&1 || true
  got="$(sh_out appops get "$PKG_APP" WRITE_SETTINGS || true)"
  [[ "$got" == *allow* ]] || warn "WRITE_SETTINGS appop reads '${got:-unset}', expected allow"
  sh_has "Device Owner" dumpsys device_policy || fail "device owner did not stick"
  ok "device owner verified"
fi

# ===================================================== 4. install extras =====

if run_stage 4; then
  banner 4
  adbx install -r "$APK_FLAUNCHER" >/dev/null || fail "FLauncher install failed"
  pkg_installed "$PKG_FLAUNCHER" || fail "$PKG_FLAUNCHER missing after install"
  ok "FLauncher installed"

  adbx install -r "$APK_ANYDESK" >/dev/null || fail "AnyDesk install failed"
  adbx install -r "$APK_ADCONTROL" >/dev/null || fail "AdControl install failed"
  pkg_installed "$PKG_ANYDESK"   || fail "$PKG_ANYDESK missing after install"
  pkg_installed "$PKG_ADCONTROL" || fail "$PKG_ADCONTROL missing after install"
  ok "AnyDesk + AdControl installed"

  # INJECT_EVENTS is signature|privileged, so a /data/app install normally
  # cannot hold it. It is granted here only because the ROM is an eng build on
  # public AOSP test keys. On a production-signed ROM this reads granted=false
  # and AnyDesk shows the screen but cannot click it — the fix is pushing the
  # plugin to /system/priv-app on a rooted box, which is a system-partition
  # change and a deliberate decision, so this warns rather than failing.
  if sh_has "INJECT_EVENTS: granted=true" dumpsys package "$PKG_ADCONTROL"; then
    ok "INJECT_EVENTS granted — AnyDesk remote input will work"
  elif (( STRICT_INJECT )); then
    fail "INJECT_EVENTS not granted to $PKG_ADCONTROL (--strict-inject)"
  else
    warn "INJECT_EVENTS NOT granted — AnyDesk will be view-only on this box.
        Production-signed ROM? See runbook §2.3."
  fi

  warn "manual step remains (runbook §2.4): open AnyDesk on the box once to
        generate its ID, enable the AdControl plugin in AnyDesk's settings, and
        record the ID in the fleet inventory. Needs a remote or mouse."
fi

# ======================================================= 5. network gate =====

if run_stage 5; then
  banner 5
  # "metrics posted" is the only proof that means the internet, not merely an
  # association with an AP. Give the app a moment first: MetricsReporter waits
  # up to 60 s for validated internet on each tick, and a cold boot's WiFi
  # association measured ~17 s on these boxes.
  online=0
  if sh_has "metrics posted" logcat -d -s MetricsReporter:D; then
    online=1
  else
    info "no 'metrics posted' yet — waiting up to 90s for the app to report"
    adbx shell monkey -p "$PKG_APP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    for _ in $(seq 1 9); do
      sleep 10
      if sh_has "metrics posted" logcat -d -s MetricsReporter:D; then online=1; break; fi
    done
  fi

  if (( online )); then
    ssid="$(sh_out dumpsys wifi | grep -oE 'SSID: [^,]*' | head -1 | sed 's/^SSID: *//')"
    ok "internet reachable — metrics posted${ssid:+ (SSID $ssid)}"
  else
    cat >&2 <<EOF

  ${C_ERR}STOP — stage 5: the box has no validated internet.${C_OFF}

  There is no adb path to fix this on API $SDK. 'cmd wifi connect-network' is
  API 29+. Pick one, by hand, on the box:

    a) The app's own captive portal. It is already running: the box raises a
       hotspot a minute after a boot with no internet. Join it from a phone and
       submit SSID 'cyma' / password '102030'. The on-screen cards are disabled
       (SHOW_WIFI_SETUP_UI = false), so read the hotspot credentials from:
           adb -s $SERIAL logcat -d | grep -i -E "SoftAp|hotspot|SSID"

    b) Settings, with a remote or mouse on the box:
           adb -s $SERIAL shell am start -a android.settings.WIFI_SETTINGS

  Then resume — earlier stages are idempotent, so this is safe either way:

      scripts/provision.sh --serial $SERIAL --from 5

EOF
    exit 10
  fi
fi

# =========================================================== 6. launcher =====

if run_stage 6; then
  banner 6
  home_now="$(sh_out cmd package resolve-activity -c android.intent.category.HOME \
              -a android.intent.action.MAIN | grep -m1 packageName | cut -d= -f2)"
  info "HOME currently resolves to ${home_now:-<unknown>}"

  if [[ "$home_now" == "$PKG_FLAUNCHER" ]]; then
    skip "FLauncher is already HOME"
  else
    # Back up the launcher before removing it. It is a user app: once
    # uninstalled there is no copy left on the box. The install path's -N suffix
    # varies per box, so it is read from pm path and never hardcoded.
    if [[ "$KEEP_OLD_LAUNCHER" == 0 ]] && pkg_installed "$PKG_OLD_LAUNCHER"; then
      old_path="$(sh_out pm path "$PKG_OLD_LAUNCHER" | head -1 | cut -d: -f2)"
      [[ -n "$old_path" ]] || fail "pm path returned nothing for $PKG_OLD_LAUNCHER"
      mkdir -p "$BACKUP_DIR"
      backup="$BACKUP_DIR/${PKG_OLD_LAUNCHER}-${SERIAL}.apk"
      adbx pull "$old_path" "$backup" >/dev/null || fail "could not pull $old_path"
      # A pull that "succeeds" into a 0-byte file is the failure mode worth
      # catching here — after the uninstall it is unrecoverable.
      [[ -s "$backup" ]] || fail "backup $backup is empty — refusing to uninstall the launcher"
      ok "backed up OEM launcher -> $backup ($(du -h "$backup" | cut -f1))"
    fi

    adbx shell cmd package set-home-activity "$FLAUNCHER_ACTIVITY" >/dev/null 2>&1 || true

    # Prove the new launcher actually starts on this ROM *before* burning the
    # old one, so the box is never left with no home app.
    adbx shell am start -n "$FLAUNCHER_ACTIVITY" >/dev/null 2>&1 || fail "FLauncher would not start"
    sleep 3
    crash="$(sh_out logcat -d | grep -E "FATAL EXCEPTION|ANR in" | grep -i flauncher || true)"
    [[ -z "$crash" ]] || fail "FLauncher crashed on start, OEM launcher left in place:
       $crash"
    ok "FLauncher starts cleanly"

    if [[ "$KEEP_OLD_LAUNCHER" == 0 ]] && pkg_installed "$PKG_OLD_LAUNCHER"; then
      adbx shell pm uninstall "$PKG_OLD_LAUNCHER" >/dev/null 2>&1 || warn "uninstall of $PKG_OLD_LAUNCHER reported failure"
      ok "OEM launcher removed"
    fi

    # Not optional. The uninstall drops the preferred-activity entry and HOME
    # falls back to packageName=android — the "pick a launcher" chooser.
    adbx shell cmd package set-home-activity "$FLAUNCHER_ACTIVITY" >/dev/null 2>&1 || true
  fi

  home_now="$(sh_out cmd package resolve-activity -c android.intent.category.HOME \
              -a android.intent.action.MAIN | grep -m1 packageName | cut -d= -f2)"
  [[ "$home_now" == "$PKG_FLAUNCHER" ]] || fail "HOME resolves to '${home_now:-<nothing>}', expected $PKG_FLAUNCHER"

  # Confirm it reached disk, so it survives a reboot. grep -c prints 0 and exits
  # 1 when there is no match: the count has to be tested, not the output.
  persisted="$(sh_out grep -c flauncher /data/system/users/0/package-restrictions.xml || true)"
  [[ "${persisted:-0}" =~ ^[0-9]+$ ]] && (( persisted > 0 )) \
    || warn "no flauncher entry in package-restrictions.xml — HOME may not survive a reboot"
  ok "FLauncher is HOME (persisted: ${persisted:-0} entries)"

  adbx shell monkey -p "$PKG_APP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  info "signage brought back to the foreground"
fi

# ========================================================== 7. wallpaper =====

if run_stage 7; then
  banner 7
  if [[ "${HAVE_ROOT:-0}" != "1" ]]; then
    skip "no root — set the wallpaper from FLauncher's Settings > Wallpaper (runbook §4.1 path B)"
  else
    # app_flutter/ is created by the Flutter engine at first launch, not by the
    # installer, so FLauncher has to have run at least once. Stage 6 starts it;
    # start it again here so --from 7 works on its own.
    adbx shell am start -n "$FLAUNCHER_ACTIVITY" >/dev/null 2>&1 || true
    sleep 3

    adbx push "$WALLPAPER" /sdcard/wallpaper.png >/dev/null || fail "push of $WALLPAPER failed"

    su_script <<EOS
set -e
P=/data/data/$PKG_FLAUNCHER
U=\$(stat -c %u:%g \$P)
mkdir -p \$P/app_flutter
cp /sdcard/wallpaper.png \$P/app_flutter/wallpaper
chown \$U \$P/app_flutter \$P/app_flutter/wallpaper
chmod 600 \$P/app_flutter/wallpaper
restorecon -R \$P/app_flutter
EOS

    adbx shell rm -f /sdcard/wallpaper.png >/dev/null 2>&1 || true

    if (( ! DRY_RUN )); then
      # A file left owned by root, or labelled sdcard_file, is unreadable by the
      # app and FLauncher falls back to its gradient *silently* — which looks
      # exactly like the copy never happening. Compare against the data dir's
      # own uid rather than trusting the copy.
      want="$(printf 'stat -c %%u /data/data/%s\n' "$PKG_FLAUNCHER" | su_script | tr -d '\r' | tail -1)"
      got="$(printf 'stat -c %%u /data/data/%s/app_flutter/wallpaper\n' "$PKG_FLAUNCHER" | su_script | tr -d '\r' | tail -1)"
      [[ -n "$got" ]] || fail "wallpaper file not present after copy"
      [[ "$got" == "$want" ]] || fail "wallpaper owned by uid $got, app runs as $want — FLauncher cannot read it"
      ok "wallpaper installed (uid $got)"
    fi

    adbx shell am force-stop "$PKG_FLAUNCHER" >/dev/null 2>&1 || true
    adbx shell monkey -p "$PKG_APP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    # wallpaper.png is pure black, so a working install and a broken one look
    # identical on screen. The uid assertion above is the real check; do not
    # replace it with a screenshot.
    info "wallpaper is solid black by design — verify by uid/label, not by eye"
  fi
fi

# ======================================================= 8. google stack =====

if run_stage 8; then
  banner 8
  for p in "${GOOGLE_PKGS[@]}"; do
    if ! pkg_installed "$p"; then info "$p not present on this ROM"; continue; fi
    if sh_hasx "package:$p" pm list packages -d; then skip "$p already disabled"; continue; fi
    adbx shell pm disable-user --user 0 "$p" >/dev/null 2>&1 || true
    if sh_hasx "package:$p" pm list packages -d; then ok "$p disabled"
    else warn "$p did not disable"; fi
  done
  info "Play Store noise in logcat right after installs is post-install checks, not a crash loop"
fi

# ========================================================= 9. acceptance =====

if run_stage 9; then
  banner 9
  rc=0

  pid="$(sh_out pidof "$PKG_APP" | tr ' ' '\n' | head -1)"
  [[ -n "$pid" ]] && ok "signage running (pid $pid)" || { warn "signage not running"; rc=1; }

  sh_has "metrics posted" logcat -d -s MetricsReporter:D \
    && ok "metrics posted — real internet" || { warn "no 'metrics posted' in the log buffer"; rc=1; }

  sh_has "Device Owner" dumpsys device_policy \
    && ok "device owner claimed" || { warn "device owner NOT claimed"; rc=1; }

  home_now="$(sh_out cmd package resolve-activity -c android.intent.category.HOME \
              -a android.intent.action.MAIN | grep -m1 packageName | cut -d= -f2)"
  [[ "$home_now" == "$PKG_FLAUNCHER" ]] && ok "HOME is FLauncher" || { warn "HOME is ${home_now:-<nothing>}"; rc=1; }

  # No AudioTrack thread. The framework's AudioTrackThread spins at ~96% of a
  # core on these boxes instead of sleeping, which is why the app builds its
  # ExoPlayer with no audio renderers at all. A thread here means the box is
  # running a build from before that fix.
  if [[ -n "$pid" ]]; then
    n="$(sh_out "for t in /proc/$pid/task/*; do cat \$t/comm; done" | grep -c AudioTrack || true)"
    (( ${n:-0} == 0 )) && ok "no AudioTrack threads" || { warn "$n AudioTrack thread(s) — reinstall from main"; rc=1; }
  fi

  # Above the first thermal trip the kernel takes cpu2/cpu3 offline and caps the
  # clock, and the box never recovers on its own. Fit a fan.
  raw="$(sh_out cat /sys/class/thermal/thermal_zone0/temp || true)"
  if [[ "$raw" =~ ^[0-9]+$ ]]; then
    c=$(( raw > 1000 ? raw / 1000 : raw ))
    if (( c < 80 )); then ok "SoC ${c}°C"
    else warn "SoC ${c}°C — at or near the 85°C trip; fit a fan"; rc=1; fi
  fi
  online="$(sh_out cat /sys/devices/system/cpu/online || true)"
  [[ "$online" == "0-3" ]] && ok "all cores online (0-3)" || warn "cores online: ${online:-?}"

  echo
  if (( rc == 0 )); then
    echo "${C_OK}provisioned — $SERIAL passed every acceptance check${C_OFF}"
  else
    echo "${C_WARN}provisioned with warnings — review the WARN lines above${C_OFF}"
  fi
  echo "${C_DIM}manual, still outstanding: AnyDesk first launch + plugin enable (§2.4); heatsink fan (§0)${C_OFF}"
  exit "$rc"
fi
