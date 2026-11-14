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
FPS=25
WIDTH=300
mkdir -p "$OUT"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

for clip in themes status dark; do
  segments=$(ls "$IN"/demo-"$clip"[0-9].mp4 2>/dev/null | sort || true)
  if [ -z "$segments" ]; then
    echo "skip $clip: no recordings"
    continue
  fi
  list="$WORK/$clip.txt"
  : > "$list"
  for segment in $segments; do echo "file '$(realpath "$segment")'" >> "$list"; done
  ffmpeg -v error -y -f concat -safe 0 -i "$list" \
    -filter_complex "[0:v]setpts=(PTS-STARTPTS)/$SLOW,fps=$FPS,scale=$WIDTH:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=192:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
    -loop 0 "$OUT/v2-$clip.gif"
  echo "$OUT/v2-$clip.gif: $(du -k "$OUT/v2-$clip.gif" | cut -f1) KB," \
    "$(ffprobe -v error -count_frames -select_streams v -show_entries stream=nb_read_frames -of csv=p=0 "$OUT/v2-$clip.gif") frames"
done
