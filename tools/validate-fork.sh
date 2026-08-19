#!/usr/bin/env bash
#
# validate-fork.sh — validate the DC-1 delta against *current* upstream HEAD
# without running a full Android build. CI runs this after every upstream
# release; run it locally before rebuilding.
#
# Checks:
#   1. device/phh/treble HEAD still accepts our product fragment via
#      `generate.sh vendor/dc1/common.mk` (the entire plumbing of the delta).
#   2. Every file referenced by common.mk exists in this repo.
#   3. The fork's manifest target (this repo) is reachable.
#   4. The LineageOS base branch + product file we inherit still exist.
#   5. Any patches/*.patch still apply cleanly to fresh upstream clones
#      (currently: none, by design — see patches/README.md).
#
# Usage: validate-fork.sh [FRAGMENT]     FRAGMENT default vendor/dc1/common.mk
# Exit 0 = delta valid, 1 = something drifted.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRAGMENT="${1:-vendor/dc1/common.mk}"
WORK="${TMPDIR:-/tmp}/dc1-validate"
mkdir -p "$WORK"

fail() { echo "VALIDATE-FAIL: $*" >&2; exit 1; }
ok()   { echo "  ok: $*"; }

api_file() { # <repo> <path> <ref>
  local url="https://raw.githubusercontent.com/$1/$3/$2"
  curl -fsSL "$url" >/dev/null 2>&1
}

echo "== 1/5 fragment contract (device/phh/treble HEAD) =="
PHH="$WORK/device_phh_treble"
if [ ! -d "$PHH/.git" ]; then
  git clone --quiet --depth 1 https://github.com/TrebleDroid/device_phh_treble "$PHH"
fi
( cd "$PHH" && git fetch --quiet --depth 1 origin HEAD && git reset --quiet --hard FETCH_HEAD )
( cd "$PHH" && bash generate.sh "$FRAGMENT" >/dev/null )
test -f "$PHH/treble_arm64_bvN.mk" || fail "generate.sh no longer emits treble_arm64_bvN.mk"
grep -q "$FRAGMENT" "$PHH/treble_arm64_bvN.mk" \
  || fail "generated product no longer inherits $FRAGMENT"
grep -q 'include build/make/target/product/aosp_arm64.mk' "$PHH/treble_arm64_bvN.mk" \
  || fail "generated product lost its base (aosp_arm64)"
( cd "$PHH" && rm -f AndroidProducts.mk treble_*.mk )
ok "generate.sh '$FRAGMENT' produces a valid treble_arm64_bvN product"

echo "== 2/5 fragment references exist locally =="
# references in common.mk that must resolve within this repo
for ref in \
  AmberControl/Android.bp \
  privapp-permissions-dc1.xml \
  sepolicy/dc1amber.te \
  local_manifests/dc1.xml; do
  [ -e "$ROOT/$ref" ] || fail "common.mk references missing file: $ref"
  ok "$ref"
done

echo "== 3/5 fork manifest target reachable =="
FORK_REPO="$(sed -n 's/.*<project[^>]*name="\([^"]*\)".*/\1/p' "$ROOT/local_manifests/dc1.xml" | head -1)"
if [ -n "$FORK_REPO" ]; then
  api_file "$FORK_REPO" "README.md" "main" \
    || fail "fork repo $FORK_REPO@main not reachable"
  ok "$FORK_REPO@main reachable"
fi

echo "== 4/5 LineageOS base present on lineage-23.2 =="
api_file "LineageOS/android_vendor_lineage" "config/common_full_phone.mk" "lineage-23.2" \
  || fail "vendor/lineage/config/common_full_phone.mk missing on lineage-23.2"
ok "vendor/lineage/config/common_full_phone.mk on lineage-23.2"

echo "== 5/5 upstream patchset (patches/*.patch) applies cleanly =="
applied=0
for p in "$ROOT"/patches/*.patch; do
  [ -e "$p" ] || continue
  proj="$(basename "$p")"; proj="${proj%%__*}"
  case "$proj" in
    device_phh_treble) clone="$PHH" ;;
    *) fail "patch $p names unknown project $proj (see patches/README.md)" ;;
  esac
  git -C "$clone" apply --check "$p" || fail "patch $p no longer applies to $proj HEAD"
  ok "$(basename "$p") applies to $proj HEAD"
  applied=1
done
[ "$applied" -eq 0 ] && ok "no upstream patches to apply (delta is patchless)"

echo
echo "VALIDATE-PASS — DC-1 delta is compatible with current upstream."
