#!/usr/bin/env bash
# r1.sh — dev loop for the Rabbit R1 launcher.
#
#   ./r1.sh                 build + install + restart   (the default loop)
#   ./r1.sh build           assembleDebug only
#   ./r1.sh install         install the last build + restart
#   ./r1.sh release         assembleRelease (R8-minified, what CI ships)
#   ./r1.sh log [tags...]   filtered logcat, follows
#   ./r1.sh shot [name]     screenshot -> shots/
#   ./r1.sh rec [secs]      screen recording -> shots/
#   ./r1.sh mirror          live screen via scrcpy (touch + keyboard work)
#   ./r1.sh key <K...>      wake, then send keycodes (DPAD_DOWN, CENTER, BACK)
#   ./r1.sh wake            wake the screen
#   ./r1.sh sh <cmd...>     adb shell (unprivileged)
#   ./r1.sh root <cmd...>   run as ROOT through carroot on 127.0.0.1:1337
#   ./r1.sh keys            wheel/side-button key events for driving the UI
#   ./r1.sh wifi            switch adb to Wi-Fi, then unplug the cable
#   ./r1.sh doctor          check device state + launcher version floor
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# shellcheck source=/dev/null
[ -r "$HOME/.config/android-dev.env" ] && . "$HOME/.config/android-dev.env"
ADB="${ADB:-adb}"
PKG=com.r1.launcher
ACT="$PKG/.LauncherActivity"
APK=app/build/outputs/apk/debug/app-debug.apk

die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
say() { printf '\033[36m==>\033[0m %s\n' "$*"; }

[ -f gradle/wrapper/gradle-wrapper.jar ] || die "run ./bootstrap.sh first"

need_device() {
  local n
  n=$("$ADB" devices | grep -cw device || true)
  [ "$n" -ge 1 ] || die "no device. Plug in the R1 (or './r1.sh wifi' if already paired), then 'adb devices'"
}

# The /system/app copy is the install floor. Read it off the device so the
# debug build always outranks it -- no hand-edited versionCode, no skip-worktree.
device_floor() {
  "$ADB" shell dumpsys package "$PKG" 2>/dev/null \
    | grep -oE 'versionCode=[0-9]+' | grep -oE '[0-9]+' | sort -n | tail -1
}

build() {
  local args=(assembleDebug)
  if "$ADB" devices | grep -qw device; then
    local floor; floor=$(device_floor || true)
    if [ -n "${floor:-}" ]; then
      args+=("-Pr1.versionCode=$((floor + 1))")
      say "device floor is versionCode=$floor -> building $((floor + 1))"
    fi
  fi
  say "./gradlew ${args[*]}"
  ./gradlew "${args[@]}"
}

install() {
  need_device
  [ -f "$APK" ] || die "no APK at $APK -- run './r1.sh build'"
  say "installing $(du -h "$APK" | cut -f1)"
  "$ADB" install -r "$APK"
  restart
}

# `am start` alone only foregrounds the existing process, so new code never
# loads. The launcher is the HOME app, so force-stop must come first.
restart() {
  say "restarting launcher"
  "$ADB" shell "am force-stop $PKG; am start -n $ACT" >/dev/null
}

case "${1:-all}" in
  all)      build; install ;;
  build)    build ;;
  install)  install ;;
  release)  say "assembleRelease"; ./gradlew assembleRelease ;;

  log)
    need_device; shift || true
    if [ $# -gt 0 ]; then
      exec "$ADB" logcat -s "$@"
    fi
    # Default: the launcher's own tags plus anything that would kill it.
    exec "$ADB" logcat -s LauncherActivity:V OpenClaw:V GatewaySession:V \
         OTAUpdater:V R1WebServer:V R1Motor:V \
         AndroidRuntime:E ActivityManager:E
    ;;

  shot)
    need_device; mkdir -p shots
    out="shots/${2:-shot-$(date +%H%M%S)}.png"
    "$ADB" exec-out screencap -p > "$out"
    say "$out  ($(du -h "$out" | cut -f1))"
    ;;

  rec)
    need_device; mkdir -p shots
    secs="${2:-20}"
    out="shots/rec-$(date +%H%M%S).mp4"
    say "recording ${secs}s -- interact with the device now"
    # No --size: let screenrecord use the native framebuffer. The R1 panel is
    # 480x640 (`wm size`), not the 480x480 the repo's docs claim -- forcing a
    # square here squashed the output.
    "$ADB" shell screenrecord --time-limit "$secs" /sdcard/r1rec.mp4
    "$ADB" pull -a /sdcard/r1rec.mp4 "$out" >/dev/null
    "$ADB" shell rm -f /sdcard/r1rec.mp4
    say "$out"
    ;;

  mirror)
    need_device
    command -v scrcpy >/dev/null || die "scrcpy not installed (pacman -S scrcpy)"
    # Width only, so scrcpy keeps the panel's real 480x640 aspect.
    # --stay-awake stops it sleeping mid-session.
    exec scrcpy --window-title "R1" --stay-awake --window-width 480
    ;;

  # Scripted input eats its first event waking a slept screen, which silently
  # desyncs a whole key sequence. Always wake, settle, then send.
  key)
    need_device; shift
    [ $# -gt 0 ] || die "usage: ./r1.sh key DPAD_DOWN [DPAD_CENTER ...]"
    "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    sleep 0.6
    for k in "$@"; do
      case "$k" in KEYCODE_*) code="$k" ;; *) code="KEYCODE_$k" ;; esac
      "$ADB" shell input keyevent "$code"
      sleep 0.5
    done
    say "sent: $*"
    ;;

  wake) need_device; "$ADB" shell input keyevent KEYCODE_WAKEUP; say "awake" ;;

  sh)  need_device; shift; exec "$ADB" shell "$@" ;;

  # carroot is an unauthenticated root shell the ROM parks on 127.0.0.1:1337.
  # adb shell is uid 2000, so this is how you get root without `adb root`.
  root)
    need_device; shift
    [ $# -gt 0 ] || die "usage: ./r1.sh root <command>"
    "$ADB" shell "echo '$*' | toybox nc 127.0.0.1 1337"
    ;;

  keys)
    cat <<'EOF'
Drive the launcher UI without touching it:

  adb shell input keyevent KEYCODE_DPAD_UP      # wheel up
  adb shell input keyevent KEYCODE_DPAD_DOWN    # wheel down
  adb shell input keyevent KEYCODE_DPAD_CENTER  # wheel click / activate
  adb shell input keyevent KEYCODE_BACK         # back

The side button is remapped to BUTTON_1 by the ROM's keylayout
(device/rabbit/r1/keylayout/mtk-kpd.kl: key 116 BUTTON_1 WAKE).
Its tap/double-tap/long-press state machine lives in
LauncherActivity.dispatchKeyEvent -- injected events skip the real
DOWN/UP timing, so long-press must be tested on hardware.

Trace presses:  ./r1.sh log | grep BUTTON_1
EOF
    ;;

  wifi)
    need_device
    ip=$("$ADB" shell ip -4 addr show wlan0 | grep -oE 'inet [0-9.]+' | cut -d' ' -f2 | head -1)
    [ -n "$ip" ] || die "no wlan0 IP -- connect the R1 to Wi-Fi first"
    "$ADB" tcpip 5555 >/dev/null; sleep 2
    "$ADB" connect "$ip:5555"
    say "connected to $ip:5555 -- you can unplug the cable"
    say "NOTE: adb over TCP is unauthenticated. Trusted networks only."
    ;;

  doctor)
    say "adb devices"; "$ADB" devices -l
    if "$ADB" devices | grep -qw device; then
      say "launcher on device"
      "$ADB" shell dumpsys package "$PKG" | grep -E 'versionCode|versionName|codePath' | sed 's/^/  /' | sort -u
      say "is it HOME?"
      "$ADB" shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | grep -E 'packageName|name=' | sed 's/^/  /'
      say "carroot root shell"
      if "$ADB" shell "echo id | toybox nc 127.0.0.1 1337" 2>/dev/null | grep -q 'uid=0'; then
        printf '  \033[32m OK \033[0m carroot responding as root on 127.0.0.1:1337\n'
      else
        printf '  \033[33mWARN\033[0m carroot not reachable -- root-backed toggles will silently no-op\n'
      fi
      say "ro.carrot.*"
      "$ADB" shell getprop | grep -E 'ro\.carrot' | sed 's/^/  /' || echo "  (none -- not a CarrotOS build?)"
    fi
    ;;

  *) grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
