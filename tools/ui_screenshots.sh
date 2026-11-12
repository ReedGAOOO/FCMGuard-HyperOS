#!/usr/bin/env bash
# Drives FCM Guard on a running Android emulator and saves screenshots for UI review.
#
#   tools/ui_screenshots.sh <apk> <output-dir>
#
# Used by .github/workflows/ui-screenshots.yml. This is stock Android, not HyperOS:
# it checks layout, theming, glass, languages and the collapse animation, not the
# Xiaomi-specific whitelist / Autostart behaviour.
set -uo pipefail

APK="$1"
OUT="$2"
PKG=com.reed.fcmguard
ACTIVITY="$PKG/.MainActivity"
UI_XML=/tmp/fcmguard-ui.xml
mkdir -p "$OUT"

log() { echo "[ui] $*"; }

shot() {
  sleep "${2:-1.5}"
  adb exec-out screencap -p > "$OUT/$1.png"
  log "captured $1"
}

# Prints "x y" for the centre of the first node whose text or content-desc fully
# matches the regex (case-insensitive); prints nothing if absent. With a second
# argument, nodes belonging to that package are ignored (used to find system dialogs).
find_node() {
  adb shell uiautomator dump /sdcard/fcmguard-ui.xml >/dev/null 2>&1 || return 0
  adb pull /sdcard/fcmguard-ui.xml "$UI_XML" >/dev/null 2>&1 || return 0
  python3 - "$1" "$UI_XML" "${2:-}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pattern = re.compile(sys.argv[1], re.I)
exclude = sys.argv[3]
for node in ET.parse(sys.argv[2]).iter('node'):
    if exclude and node.get('package') == exclude:
        continue
    for text in (node.get('text') or '', node.get('content-desc') or ''):
        if text and pattern.fullmatch(text.strip()):
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
PY
}

tap_text() {
  local pos
  pos=$(find_node "$1")
  if [ -n "$pos" ]; then
    adb shell input tap $pos
    log "tapped '$1' at $pos"
    return 0
  fi
  log "not on screen: '$1'"
  return 1
}

app_is_resumed() {
  adb shell dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity:' | head -n 1 | grep -q "$PKG/"
}

# Legacy (targetSdk 22) apps trigger system UI on stock Android: the "built for an
# older version of Android" warning and notification prompts. Tap any such button
# that does not belong to the app; relaunch only if the app is not resumed. Done once
# the app has been resumed with no dialog for two consecutive checks.
settle_app() {
  local pos calm=0
  for _ in $(seq 1 12); do
    pos=$(find_node 'continue|ok|allow|got it' "$PKG")
    if [ -n "$pos" ]; then
      adb shell input tap $pos
      log "dismissed system dialog at $pos"
      calm=0
      sleep 2
      continue
    fi
    if app_is_resumed; then
      calm=$((calm + 1))
      [ "$calm" -ge 2 ] && return 0
    else
      calm=0
      adb shell am start -n "$ACTIVITY" >/dev/null 2>&1
    fi
    sleep 1.5
  done
  log "app did not settle"
}

# Fresh process, scrolled to the top.
launch_fresh() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACTIVITY" >/dev/null
  sleep 2
  settle_app
}

# Drags the content up by $1 px and holds still before lifting, so the ScrollView
# does not fling and every screenshot shows a deterministic scroll position.
drag_up() {
  local dist=$1 x=540 y=1900 steps=8 i
  adb shell input motionevent DOWN $x $y
  for i in $(seq 1 $steps); do
    adb shell input motionevent MOVE $x $((y - dist * i / steps))
  done
  sleep 0.8
  adb shell input motionevent UP $x $((y - dist))
}

scroll_until_visible() {
  local pattern=$1 i
  for i in 1 2 3 4 5 6; do
    if [ -n "$(find_node "$pattern")" ]; then return 0; fi
    drag_up 500
    sleep 1
  done
  return 1
}

adb wait-for-device
adb shell wm size
adb shell wm density

adb install -r -t --bypass-low-target-sdk-block "$APK" || adb install -r -t "$APK"
adb shell appops set "$PKG" WRITE_SETTINGS allow
# Skip the legacy-app permission review screen (Android 10+).
adb shell pm clear-permission-flags "$PKG" android.permission.POST_NOTIFICATIONS review-required || true
adb logcat -c || true
adb shell cmd uimode night no

# --- Light, English, 411dp ---
launch_fresh
shot 01-light-initial
tap_text 'repair now' && shot 02-light-repaired
tap_text 'automatic protection' && sleep 2 && settle_app
shot 03-light-protected
drag_up 250;  shot 04-light-scroll-250
drag_up 250;  shot 05-light-scroll-500
drag_up 200;  shot 06-light-scroll-700
drag_up 300;  shot 06b-light-scroll-1000
# Fully stacked: throw the front row out with a fast swipe, then try a short slow drag
# that should spring back. The most severe tile is in the front row.
front=$(find_node 'fcm guard whitelist|modify settings')
if [ -n "$front" ]; then
  set -- $front
  adb shell input swipe "$1" "$2" $(( $1 + 700 )) "$2" 90
  shot 06c-light-swiped-out 1.5
  front=$(find_node 'modify settings|automatic protection|required item present|background mode|fcm guard autostart')
  if [ -n "$front" ]; then
    set -- $front
    adb shell input swipe "$1" "$2" $(( $1 + 120 )) $(( $2 + 40 )) 1500
    shot 06d-light-sprung-back 1.5
  fi
fi
if scroll_until_visible 'scan fcm apps'; then
  tap_text 'scan fcm apps' && shot 07-light-fcm-scan 4
fi
for _ in 1 2 3 4; do drag_up 900; done
shot 08-light-bottom

# --- Dark ---
adb shell cmd uimode night yes
launch_fresh
shot 09-dark-top
drag_up 500;  shot 10-dark-scroll-500
drag_up 700;  shot 11-dark-scroll-1200
for _ in 1 2 3 4; do drag_up 900; done
shot 12-dark-bottom

# --- Simplified Chinese (per-app locale, Android 13+) ---
adb shell cmd uimode night no
adb shell cmd locale set-app-locales "$PKG" --locales zh-CN
launch_fresh
shot 13-zh-top
drag_up 500;  shot 14-zh-scroll-500
for _ in 1 2 3 4 5; do drag_up 900; done
shot 15-zh-bottom
adb shell cmd locale set-app-locales "$PKG" --locales ""

# --- Compact width: 360dp ---
adb shell wm density 480
launch_fresh
shot 16-360dp-top
drag_up 500;  shot 17-360dp-scroll-500
adb shell wm density reset

# --- Colour palettes: hero capsule, picker, every palette in light and dark ---
# Animations are off for deterministic shots; turn them on here so the page crossfade
# runs, and record one palette switch to check it frame by frame.
pick_palette() {
  tap_text 'colors' >/dev/null || return 1
  sleep 1
  tap_text "$1" || { adb shell input keyevent 4; return 1; }
  shot "$2" 1.8
}
adb shell settings put global animator_duration_scale 1
adb shell cmd uimode night no
launch_fresh
tap_text 'colors' && shot 18-palette-picker-light 1.2
adb shell screenrecord --time-limit 6 /sdcard/palette-switch.mp4 >/dev/null 2>&1 &
REC=$!
sleep 0.5
tap_text 'aurora'
wait "$REC" || true
adb pull /sdcard/palette-switch.mp4 "$OUT/palette-switch.mp4" >/dev/null 2>&1 || log "no screen recording"
shot 19-palette-aurora-light 0.5
n=20
for p in 'hyperos blue' 'guardian teal' 'indigo dusk' 'google classic' 'sakura' 'sunset amber' 'graphite'; do
  pick_palette "$p" "$n-palette-${p// /-}-light"
  n=$((n + 1))
done
# System dark mode while the app is open: handled in-process with the same crossfade.
adb shell cmd uimode night yes
shot 27-palette-graphite-dark 3
tap_text 'colors' && shot 28-palette-picker-dark 1.2
adb shell input keyevent 4
n=29
for p in 'aurora' 'hyperos blue' 'guardian teal' 'indigo dusk' 'google classic' 'sakura' 'sunset amber' 'classic'; do
  pick_palette "$p" "$n-palette-${p// /-}-dark"
  n=$((n + 1))
done
adb shell cmd uimode night no
adb shell settings put global animator_duration_scale 0

# Any crash of the app during the run ends up here.
adb logcat -d -b crash > "$OUT/crash-log.txt" 2>/dev/null || true
[ -s "$OUT/crash-log.txt" ] && { log "CRASH LOG:"; cat "$OUT/crash-log.txt"; } || rm -f "$OUT/crash-log.txt"

# Smaller files for review; keep PNG if ImageMagick is unavailable.
if command -v convert >/dev/null 2>&1; then
  for f in "$OUT"/*.png; do
    convert "$f" -quality 85 "${f%.png}.jpg" && rm -f "$f"
  done
fi
ls -la "$OUT"
