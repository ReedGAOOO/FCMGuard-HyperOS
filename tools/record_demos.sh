# Records the README demo clips. Sourced by tools/ui_screenshots.sh (UI_PASSES=demos), which
# has installed the app and defines find_node, launch_fresh, settle_app, log, $OUT and $PKG.
#
# The CI emulator renders in software, at a few frames per second. To capture smooth motion,
# the app's animations run SLOW times slower and every gesture and pause takes SLOW times
# longer. tools/make_demo_gifs.sh then speeds the recordings up by SLOW, so the GIFs play at
# real speed. Touch-driven motion (scrolling, dragging a tile) follows the finger, so slowing
# the gesture slows it by the same factor. "Show taps" is on, so every touch is visible.
#
# Each clip is recorded in segments (demo-<clip><n>.mp4) that are joined later, so the UI
# lookups between gestures leave no dead time in the clip.

SLOW=6

# Seconds of final-video time -> seconds of real time.
hold() { sleep "$(awk "BEGIN { print $1 * $SLOW }")"; }

# Press at x,y for about 90 ms of final-video time, so the touch dot is visible.
press() { adb shell input swipe "$1" "$2" "$1" "$2" $(( 90 * SLOW )); }

# Drag from x1,y1 to x2,y2 over $5 ms of final-video time.
drag() { adb shell input swipe "$1" "$2" "$3" "$4" $(( $5 * SLOW )); }

rec_start() {
  adb shell rm -f "/sdcard/demo-$1.mp4"
  adb shell screenrecord --bit-rate 16000000 --time-limit 170 "/sdcard/demo-$1.mp4" >/dev/null 2>&1 &
  REC_PID=$!
  sleep 2 # The recorder needs a moment before its first frame.
}

rec_stop() {
  sleep 1
  adb shell 'kill -INT $(pidof screenrecord)' >/dev/null 2>&1 || true # INT finalizes the file.
  wait "$REC_PID" 2>/dev/null || true
  sleep 1
  adb pull "/sdcard/demo-$1.mp4" "$OUT/demo-$1.mp4" >/dev/null 2>&1 && log "recorded demo-$1" ||
    log "no recording: demo-$1"
}

# Prints "x y" centres for each argument: "id:<resource id>" or a text/description regex.
ui_centres() {
  adb shell uiautomator dump /sdcard/fcmguard-ui.xml >/dev/null 2>&1
  adb pull /sdcard/fcmguard-ui.xml "$UI_XML" >/dev/null 2>&1 || return 1
  python3 - "$UI_XML" "$@" <<'PY'
import re, sys, xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).iter("node"))
for query in sys.argv[2:]:
    found = "-"
    for node in nodes:
        if query.startswith("id:"):
            hit = (node.get("resource-id") or "").endswith(":id/" + query[3:])
        else:
            hit = any(t and re.fullmatch(query, t.strip(), re.I)
                      for t in (node.get("text") or "", node.get("content-desc") or ""))
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if hit and m:
            x1, y1, x2, y2 = map(int, m.groups())
            found = f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
            break
    print(found)
PY
}

log "demos: slow motion x$SLOW"
adb shell settings put system show_touches 1
adb shell settings put global animator_duration_scale "$SLOW"
adb shell cmd uimode night no
launch_fresh
# A realistic state: protection on and the whitelist repaired (FCM Guard's own whitelist
# entry and Autostart stay red/unknown: that needs HyperOS).
tap_text 'protect now' >/dev/null
sleep 2
tap_text 'automatic protection' >/dev/null && sleep 3 && settle_app
launch_fresh
sleep 4

# --- Clip "dark": the system switches to dark mode with the app open, and back. --------
rec_start dark1
hold 0.9
adb shell cmd uimode night yes
hold 1.6
adb shell cmd uimode night no
hold 1.4
rec_stop dark1
settle_app

# --- Clip "status": the card docks and stacks as the page scrolls, tiles are flicked through,
# and the stack unfolds again. --------------------------------------------------------------
rec_start status1
hold 0.6
drag 540 1950 540 850 1500
hold 0.5
rec_stop status1
read -r LEFT RIGHT < <(ui_centres id:statusTile1 id:statusTile2 | paste -sd' ' - | awk '{ print $1","$2, $3","$4 }')
log "stacked tiles at $LEFT / $RIGHT"
if [ "${LEFT#-}" = "$LEFT" ] && [ "${RIGHT#-}" = "$RIGHT" ]; then
  lx=${LEFT%,*}; ly=${LEFT#*,}; rx=${RIGHT%,*}; ry=${RIGHT#*,}
  rec_start status2
  hold 0.5
  drag "$lx" "$ly" 20 $(( ly + 40 )) 170   # Left column: flick out to the left...
  hold 0.9
  drag "$lx" "$ly" 20 $(( ly - 30 )) 150   # ...and the next one, right away.
  hold 0.9
  drag "$rx" "$ry" 1060 $(( ry + 30 )) 170 # Right column, on its own.
  hold 1.0
  rec_stop status2
fi
rec_start status3
hold 0.3
drag 540 1000 540 2100 1500
hold 1.0
rec_stop status3

# --- Clip "themes": the panel grows out of the Theme capsule, the strip is flicked through,
# two themes are picked (the page recolours behind the panel), and it shrinks back. ---------
launch_fresh
sleep 3
read -r CAPSULE < <(ui_centres 'theme' | tr ' ' ',')
cx=${CAPSULE%,*}; cy=${CAPSULE#*,}
# Dry run to learn where the strip and the two target cards are (layout is deterministic).
# A card's centre lies on its preview, which is where it is pressed and dragged.
press "$cx" "$cy"
sleep 5
read -r STRIP < <(ui_centres 'classic' | tr ' ' ',')
sy=${STRIP#*,}
for _ in 1 2 3 4 5; do drag 860 "$sy" 560 "$sy" 200; sleep 2; done
read -r PICK1 PICK2 < <(ui_centres 'sakura' 'sunset amber' | tr ' ' ',' | paste -sd' ' -)
log "theme capsule $cx,$cy strip y $sy picks $PICK1 / $PICK2"
adb shell input keyevent 4
sleep 5
if [ "${PICK1#-}" = "$PICK1" ] && [ "${PICK2#-}" = "$PICK2" ]; then
  rec_start themes1
  hold 0.7
  press "$cx" "$cy"
  hold 1.0
  for _ in 1 2 3 4 5; do drag 860 "$sy" 560 "$sy" 200; hold 0.25; done
  hold 0.5
  press "${PICK1%,*}" "${PICK1#*,}"
  hold 1.5
  press "${PICK2%,*}" "${PICK2#*,}"
  hold 1.5
  adb shell input keyevent 4
  hold 1.2
  rec_stop themes1
fi

adb shell settings put global animator_duration_scale 0
adb shell settings put system show_touches 0
