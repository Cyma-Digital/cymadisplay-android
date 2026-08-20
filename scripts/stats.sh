#!/system/bin/sh
# cyma-display system report — built for Android 7 TV boxes (toybox/mksh, no awk/bc)
#
# Push once:
#   adb push scripts/stats.sh /data/local/tmp/stats.sh
# Run:
#   adb shell sh /data/local/tmp/stats.sh          one-shot report
#   adb shell sh /data/local/tmp/stats.sh 5        auto-refresh every 5 s

PKG="com.cyma.videoloop"

hr() { echo "------------------------------------------------------------"; }

# run dumpsys with a timeout guard when available (can hang on pirate boxes)
ds() { if command -v timeout >/dev/null 2>&1; then timeout 5 "$@"; else "$@"; fi; }

# read a /proc/meminfo value in kB, normalizing pirate kernels that report bytes
mem_kb() {
  v=$(grep -m1 "^$1:" /proc/meminfo | tr -s ' \t' ' ' | sed 's/^ //' | cut -d' ' -f2)
  case "$v" in ''|*[!0-9]*) echo 0; return;; esac
  while [ "$v" -gt 16777216 ]; do v=$((v/1024)); done   # >16GB in kB = bytes lie
  echo "$v"
}

get_ip() {
  gi_iface="$1" gi_out=""
  gi_out=$(ip -f inet addr show "$gi_iface" 2>/dev/null)
  [ -n "$gi_out" ] && { echo "$gi_out" | grep -oE 'inet [0-9.]+' | head -n1 | cut -d' ' -f2; return; }
  gi_out=$(ifconfig "$gi_iface" 2>/dev/null)
  [ -n "$gi_out" ] && { echo "$gi_out" | grep -oE 'inet addr:[0-9.]+' | head -n1 | cut -d: -f2; return; }
  netcfg 2>/dev/null | grep "^$gi_iface" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' | head -n1
}

get_ssid() {
  gs_s=$(wpa_cli status 2>/dev/null | grep '^ssid=' | head -n1 | cut -d= -f2)
  if [ -n "$gs_s" ]; then echo "$gs_s"; return; fi
  ds dumpsys wifi 2>/dev/null | grep -oE 'SSID: [^,]*' | head -n1 | sed 's/^SSID: *//'
}

df_col() { df -h "$1" 2>/dev/null | tail -n 1 | tr -s ' ' | sed 's/^ //' | cut -d' ' -f"$2"; }

# one-shot system CPU % — "TOTAL:" line from dumpsys cpuinfo, else top -n 1 header
cpu_pct() {
  p=$(ds dumpsys cpuinfo 2>/dev/null | grep -m1 -oE 'TOTAL: *[0-9]+%' | grep -oE '[0-9]+')
  if [ -n "$p" ]; then echo "$p"; return; fi
  cp_line=$(top -n 1 2>/dev/null | grep -m1 -E 'User [0-9]+%')
  cp_sum=0
  for v in $(echo "$cp_line" | grep -oE '[0-9]+%'); do
    v=${v%%%}; cp_sum=$((cp_sum + v))
  done
  [ "$cp_sum" -gt 0 ] && { echo "$cp_sum"; return; }
  echo "?"
}

# print temperature in C from a raw value, auto-detecting unit; empty if bogus
temp_fmt() {
  v="$1"
  case "$v" in ''|*[!0-9]*) return 1;; esac
  [ "$v" -eq 0 ] && return 1
  if [ "$v" -ge 1000 ]; then c=$((v/1000))          # millidegrees
  elif [ "$v" -ge 200 ]; then echo "$((v/10)).$((v%10))"; return   # decidegrees
  else c="$v"                                       # already degrees
  fi
  [ "$c" -ge 15 ] && [ "$c" -le 150 ] && echo "$c"
}

main() {
  echo "============================================================"
  echo " CYMA DISPLAY - SYSTEM REPORT        $(date '+%Y-%m-%d %H:%M:%S')"
  echo "============================================================"

  # ---------- system specs ----------
  sdk=$(getprop ro.build.version.sdk)
  rel=$(getprop ro.build.version.release)
  # pirate boxes spoof release; SDK is the truth
  case "$sdk" in
    21) av="5.0";; 22) av="5.1";; 23) av="6.0";; 24) av="7.0";; 25) av="7.1";;
    26) av="8.0";; 27) av="8.1";; 28) av="9";; 29) av="10";; 30) av="11";;
    31) av="12";; 32) av="12L";; 33) av="13";; 34) av="14";;
    *) av="SDK $sdk";;
  esac
  spoof=""
  case "$rel" in
    "$av"|"$av."*) ;;
    *) spoof=" [build.prop claims $rel - spoofed]";;
  esac
  fp=$(getprop ro.build.fingerprint)
  kern=$(cut -d' ' -f3 /proc/version)
  model=$(getprop ro.product.model)
  device=$(getprop ro.product.device)
  board=$(getprop ro.board.platform)
  serial=$(getprop ro.serialno)
  cm=$(grep -m1 -E '^model name|^Processor' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')
  ch=$(grep -m1 '^Hardware' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')
  cores=$(grep -c '^processor' /proc/cpuinfo)
  online=$(cat /sys/devices/system/cpu/online 2>/dev/null)
  [ -n "$online" ] && cores="$cores (online: $online)"
  maxf=""
  for f in /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq \
           /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq; do
    [ -r "$f" ] && { maxf=$(cat "$f" 2>/dev/null); break; }
  done
  mt=$(mem_kb MemTotal)
  dsize=$(df_col /data 2)

  up=$(cut -d. -f1 /proc/uptime); up_d=$((up/86400)); up_h=$(((up%86400)/3600)); up_m=$(((up%3600)/60))

  echo "--- SPECS ---------------------------------------------------"
  printf "%-15s%s\n" "Model" "$model ($device)"
  printf "%-15s%s\n" "Board" "$board $ch"
  printf "%-15s%s\n" "Serial" "$serial"
  printf "%-15s%s\n" "Android" "$av (SDK $sdk)$spoof"
  printf "%-15s%s\n" "Fingerprint" "$fp"
  printf "%-15s%s\n" "Kernel" "$kern"
  if [ -n "$maxf" ]; then printf "%-15s%s\n" "CPU" "$cores cores, $cm, max $((maxf/1000)) MHz"
  else printf "%-15s%s\n" "CPU" "$cores cores, $cm"; fi
  printf "%-15s%s\n" "RAM total" "$((mt/1024)) MB"
  printf "%-15s%s\n" "Disk /data" "$dsize total"
  printf "%-15s%s\n" "Uptime" "${up_d}d ${up_h}h ${up_m}m"

  # ---------- cpu ----------
  curf=""
  for f in /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq \
           /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq; do
    [ -r "$f" ] && { curf=$(cat "$f" 2>/dev/null); break; }
  done
  pct=$(cpu_pct)
  if [ -n "$curf" ]; then printf "%-15s%s (%s MHz now, max %s MHz)\n" "CPU usage" "${pct}%" "$((curf/1000))" "$((maxf/1000))"
  else printf "%-15s%s\n" "CPU usage" "${pct}%"; fi
  printf "%-15s%s\n" "Load avg" "$(cut -d' ' -f1-3 /proc/loadavg)"

  tz_done=""
  tz_found=0
  for z in /sys/class/thermal/thermal_zone* /sys/devices/virtual/thermal/thermal_zone* \
           /sys/devices/platform/sunxi-tsensor; do
    [ -r "$z/temp" ] || continue
    tz_type=$(cat "$z/type" 2>/dev/null); [ -n "$tz_type" ] || tz_type=$(basename "$z")
    case "$tz_done" in *"|$tz_type|"*) continue;; esac
    tz_done="$tz_done|$tz_type|"
    tz_c=$(temp_fmt "$(cat "$z/temp" 2>/dev/null)")
    if [ -n "$tz_c" ]; then
      printf "%-15s%s C  (%s)\n" "Temperature" "$tz_c" "$tz_type"
      tz_found=1
    fi
  done
  [ "$tz_found" -eq 0 ] && printf "%-15s%s\n" "Temperature" "n/a"

  # ---------- memory ----------
  ma=$(mem_kb MemAvailable)
  if [ "$ma" -eq 0 ]; then
    ma=$(( $(mem_kb MemFree) + $(mem_kb Cached) + $(mem_kb Buffers) ))
  fi
  mu=$((mt - ma)); [ "$mu" -lt 0 ] && mu=0
  st=$(mem_kb SwapTotal); sf=$(mem_kb SwapFree)
  echo "--- MEMORY --------------------------------------------------"
  if [ "$mt" -gt 0 ]; then
    printf "%-15s%s / %s MB  (%s%% used)\n" "RAM" "$((mu/1024))" "$((mt/1024))" "$((mu*100/mt))"
    printf "%-15s%s MB\n" "RAM available" "$((ma/1024))"
  fi
  [ "$st" -gt 0 ] && printf "%-15s%s / %s MB used\n" "Swap" "$(((st-sf)/1024))" "$((st/1024))"

  # ---------- storage ----------
  echo "--- STORAGE -------------------------------------------------"
  printf "%-12s%-10s%-10s%-10s%s\n" "MOUNT" "SIZE" "USED" "AVAIL" "USE%"
  for m in /data /system /cache; do
    printf "%-12s%-10s%-10s%-10s%s\n" "$m" "$(df_col $m 2)" "$(df_col $m 3)" "$(df_col $m 4)" "$(df_col $m 5)"
  done

  # ---------- network ----------
  w_ip=$(get_ip wlan0)
  w_mac=$(cat /sys/class/net/wlan0/address 2>/dev/null)
  e_ip=$(get_ip eth0)
  e_mac=$(cat /sys/class/net/eth0/address 2>/dev/null)
  ssid=$(get_ssid)
  echo "--- NETWORK -------------------------------------------------"
  if [ -n "$w_ip" ] || [ -n "$ssid" ]; then
    printf "%-15s%s\n" "Wi-Fi SSID" "${ssid:-n/a}"
    printf "%-15s%s\n" "Wi-Fi IP" "${w_ip:-n/a}  MAC ${w_mac:-n/a}"
  else
    printf "%-15s%s\n" "Wi-Fi" "not connected"
  fi
  if [ -n "$e_ip" ]; then
    printf "%-15s%s\n" "Ethernet IP" "$e_ip  MAC ${e_mac:-n/a}"
  else
    printf "%-15s%s\n" "Ethernet" "not connected"
  fi

  # ---------- app ----------
  pid=$(pidof "$PKG" 2>/dev/null | cut -d' ' -f1)
  if [ -z "$pid" ]; then
    pid=$(ps 2>/dev/null | grep -w "$PKG" | grep -v grep | head -n1 | tr -s ' ' | sed 's/^ //' | cut -d' ' -f2)
  fi
  ver=$(ds dumpsys package "$PKG" 2>/dev/null | grep -m1 'versionName=' | sed 's/.*versionName=//' | cut -d' ' -f1)
  echo "--- APP -----------------------------------------------------"
  if [ -z "$ver" ]; then
    printf "%-15s%s\n" "$PKG" "NOT INSTALLED"
  elif [ -n "$pid" ]; then
    rss=$(grep -m1 '^VmRSS:' "/proc/$pid/status" 2>/dev/null | tr -s ' \t' ' ' | sed 's/^ //' | cut -d' ' -f2)
    printf "%-15s%s\n" "$PKG" "RUNNING (pid $pid)"
    printf "%-15s%s\n" "App version" "$ver"
    case "$rss" in ''|*[!0-9]*) ;; *) printf "%-15s%s MB\n" "App memory" "$((rss/1024))";; esac
  else
    printf "%-15s%s\n" "$PKG" "INSTALLED but NOT RUNNING (v$ver)"
  fi
  hr
}

if [ -n "$1" ]; then
  while :; do clear; main; echo "(refreshing every $1 s - Ctrl-C to stop)"; sleep "$1"; done
else
  main
fi
