#!/usr/bin/env bash
# Turns the demo recordings from tools/record_demos.sh into the README GIFs.
#
#   tools/make_demo_gifs.sh <recordings-dir> [output-dir]
#
# Each clip's segments (demo-<clip>1.mp4, demo-<clip>2.mp4, ...) are joined, sped up by
# SLOW (the recordings were made in slow motion, see record_demos.sh) so they play at real
# speed, scaled down and encoded with a per-clip palette. Needs ffmpeg.
set -euo pipefail

IN="$1"
OUT="${2:-docs/images}"
SLOW=6
FPS=20
WIDTH=300
mkdir -p "$OUT"

for clip in themes status dark; do
  segments=$(ls "$IN"/demo-"$clip"[0-9].mp4 2>/dev/null | sort || true)
  if [ -z "$segments" ]; then
    echo "skip $clip: no recordings"
    continue
  fi
  # The recorder only emits a frame when the screen changes, so the still moment at the end
  # of each segment would be lost: each segment is held on its last frame for a beat (and
  # the clip's last one a little longer) before they are joined.
  inputs=()
  filters=""
  n=0
  count=$(echo "$segments" | wc -l)
  for segment in $segments; do
    inputs+=(-i "$segment")
    tail=$(awk "BEGIN { print ($((n + 1)) == $count ? 1.2 : 0.35) * $SLOW }")
    filters+="[$n:v]setpts=PTS-STARTPTS,fps=30,tpad=stop_mode=clone:stop_duration=$tail[s$n];"
    n=$((n + 1))
  done
  joined=""
  for i in $(seq 0 $((n - 1))); do joined+="[s$i]"; done
  ffmpeg -v error -y "${inputs[@]}" -filter_complex \
    "${filters}${joined}concat=n=$n:v=1:a=0,setpts=PTS/$SLOW,fps=$FPS,scale=$WIDTH:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=160:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
    -loop 0 "$OUT/v2-$clip.gif"
  echo "$OUT/v2-$clip.gif: $(du -k "$OUT/v2-$clip.gif" | cut -f1) KB," \
    "$(ffprobe -v error -count_frames -select_streams v -show_entries stream=nb_read_frames -of csv=p=0 "$OUT/v2-$clip.gif") frames," \
    "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/v2-$clip.gif") s"
done
