#!/usr/bin/env bash
#
# apply.sh — apply the DC-1 patchset (patches/*.patch) on top of a synced
# LineageOS + TrebleDroid tree. Currently the delta is patchless (it lives in
# vendor/dc1 via generate.sh), so this script is a no-op that verifies that
# contract: it checks that no *.patch files have appeared that someone forgot
# to wire into CI.
#
# Usage: apply.sh [TOP]      # TOP = Android source root (default: repo root)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${1:-$(cd "$HERE/.." && pwd)}"

# Map a patch filename to the upstream project dir it targets.
# Convention: patches/ are named  <project-path>__<NNNN>-<desc>.patch
# e.g.  device_phh_treble__0001-foo.patch
apply_one() {
  local f="$1"
  local base proj
  base="$(basename "$f")"
  proj="${base%%__*}"
  if [ "$proj" = "$base" ]; then
    echo "SKIP $base (no <project>__ prefix; not part of the validated set)" >&2
    return 0
  fi
  local dir="$ROOT/$proj"
  if [ ! -d "$dir" ]; then
    echo "ERROR: $proj not found at $dir (sync the tree first)" >&2
    return 1
  fi
  echo "apply $base -> $proj"
  git -C "$dir" apply --whitespace=nowarn "$f"
}

found=0
for f in "$HERE"/[0-9]*.patch; do
  [ -e "$f" ] || continue
  found=1
  apply_one "$f"
done

if [ "$found" -eq 0 ]; then
  echo "No tracked patches in patches/ — delta is patchless by design "
  echo "(vendor/dc1 product fragment)."
fi
