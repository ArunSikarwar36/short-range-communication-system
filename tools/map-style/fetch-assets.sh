#!/usr/bin/env bash
# Downloads the fonts (SIL OFL) and sprites (BSD-3) used by the offline map style into assets/map.
set -euo pipefail
cd "$(dirname "$0")/../.."
BASE="https://raw.githubusercontent.com/protomaps/basemaps-assets/main"
OUT="assets/map"
RANGES="0-255 256-511 512-767 768-1023 1024-1279 1280-1535 2304-2559 7680-7935 8192-8447 8448-8703"
for stack in "Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic"; do
  mkdir -p "$OUT/fonts/$stack"
  for r in $RANGES; do
    curl -sfL "$BASE/fonts/${stack// /%20}/$r.pbf" -o "$OUT/fonts/$stack/$r.pbf"
  done
done
dev="Noto Sans Devanagari Regular v1"
mkdir -p "$OUT/fonts/$dev"
for i in $(seq 0 255); do
  r="$((i * 256))-$((i * 256 + 255))"
  curl -sfL "$BASE/fonts/${dev// /%20}/$r.pbf" -o "$OUT/fonts/$dev/$r.pbf" || rm -f "$OUT/fonts/$dev/$r.pbf"
done
# The Devanagari stack links most ranges to "../Noto Sans Regular/<range>.pbf" (git symlinks, served as
# text). Replace each link with the bundled file it points to, or drop it if that range is not bundled.
for f in "$OUT/fonts/$dev"/*.pbf; do
  if head -c 3 "$f" | grep -q '^\.\./'; then
    target="$OUT/fonts/$(cut -c4- "$f")"
    if [ -f "$target" ]; then cp "$target" "$f"; else rm -f "$f"; fi
  fi
done
curl -sfL "$BASE/fonts/OFL.txt" -o "$OUT/fonts/OFL.txt"
mkdir -p "$OUT/sprites"
for f in dark.json dark.png dark@2x.json dark@2x.png; do curl -sfL "$BASE/sprites/v4/$f" -o "$OUT/sprites/$f"; done
echo "assets ready: $(du -sh "$OUT" | cut -f1)"
