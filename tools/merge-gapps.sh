#!/usr/bin/env bash
#
# merge-gapps.sh — merge a MindTheGapps (or OpenGApps) zip into a GSI
# system.img, producing a single flashable image. Linux only (loop mounts).
#
# Usage: merge-gapps.sh <system.img> <gapps.zip> [out.img]
#
set -euo pipefail

IMG="$1"; GAPPS="$2"; OUT="${3:-${IMG%.img}-gapps.img}"
WORK="$(mktemp -d)"
trap 'cd /; sudo umount -q "$WORK/raw" 2>/dev/null || true; rm -rf "$WORK"' EXIT

[ -f "$IMG" ]   || { echo "missing: $IMG" >&2; exit 1; }
[ -f "$GAPPS" ] || { echo "missing: $GAPPS" >&2; exit 1; }

echo ">> unsparsing $IMG"
simg2img "$IMG" "$WORK/raw.img"

echo ">> mounting raw image"
mkdir -p "$WORK/raw"
sudo mount -o loop "$WORK/raw.img" "$WORK/raw"

echo ">> installing GApps files into image"
TOPDIR="$WORK/gapps" && mkdir -p "$TOPDIR"
unzip -qo "$GAPPS" -d "$TOPDIR"
for sub in Core GApps; do
  [ -d "$TOPDIR/$sub" ] || continue
  for pkg in "$TOPDIR"/$sub/*; do
    [ -d "$pkg" ] || continue
    echo "   + $(basename "$pkg") ($(du -sh "$pkg" | cut -f1))"
    d="${pkg#*_}"
    target="$WORK/raw/$d"
    # preserve existing dirs; overwrite files; never clobber system preinstall
    sudo cp -a "$pkg/." "$target/" 2>/dev/null || sudo mkdir -p "$target" && sudo cp -a "$pkg/." "$target/"
    # fix ownership/perms like typical gapps installers
    sudo chown -R root:root "$target" 2>/dev/null || true
  done
done

echo ">> re-sparsing"
sudo umount "$WORK/raw"
img2simg "$WORK/raw.img" "$OUT"
echo "Merged image: $OUT"
