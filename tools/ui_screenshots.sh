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

# Legacy (targetSdk 22) apps trigger system UI on stock Android: the permission
# review screen, the "built for an older version of Android" warning and the
# notification permission prompt. Accept any such button that does not belong to
# the app, then make sure the app is in front again.
dismiss_system_dialogs() {
  local pos empty=0
  # Some dialogs only appear once the activity has resumed, so keep looking until
  # nothing has shown up for three consecutive checks.
  for _ in $(seq 1 10); do
    pos=$(find_node 'continue|ok|allow|got it' "$PKG")
    if [ -n "$pos" ]; then
      adb shell input tap $pos
      log "dismissed system dialog at $pos"
      empty=0
      adb shell am start -n "$ACTIVITY" >/dev/null 2>&1
    else
      empty=$((empty + 1))
      [ "$empty" -ge 3 ] && break
    fi
    sleep 1.5
  done
}

# Fresh process, scrolled to the top.
launch_fresh() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACTIVITY" >/dev/null
  sleep 2
  dismiss_system_dialogs
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
adb shell cmd uimode night no

# --- Light, English, 411dp ---
launch_fresh
shot 01-light-initial
tap_text 'repair now' && shot 02-light-repaired
tap_text 'automatic protection' && sleep 2 && dismiss_system_dialogs
shot 03-light-protected
drag_up 250;  shot 04-light-scroll-250
drag_up 250;  shot 05-light-scroll-500
drag_up 500;  shot 06-light-scroll-1000
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

# Smaller files for review; keep PNG if ImageMagick is unavailable.
if command -v convert >/dev/null 2>&1; then
  for f in "$OUT"/*.png; do
    convert "$f" -quality 85 "${f%.png}.jpg" && rm -f "$f"
  done
fi
ls -la "$OUT"
