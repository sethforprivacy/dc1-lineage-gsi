#!/usr/bin/env bash
#
# build-release.sh — one-command local build of the DC-1 LineageOS GSI.
#
# Wraps the manual steps in docs/build.md and validates the delta first
# (tools/validate-fork.sh). Designed to be run from the machine that owns
# the build (Linux with ~150 GB free; the device itself is only needed later
# for flashing).
#
# Usage:
#   ./build-release.sh                      # unrooted `user` build
#   ./build-release.sh --userdebug          # userdebug (adb root; diagnostics only)
#   ./build-release.sh --with-gapps=path/MindTheGapps-*-arm64.zip   # merge GApps into image
#   ./build-release.sh -j 8                 # parallel jobs
#
# Env overrides:
#   DC1_BUILD_DIR   build tree location            (default: ~/dc1-build)
#   DC1_GITHUB      git remote for this fork       (default: https://github.com/sethforprivacy/)
#   DC1_REPO        fork repo name                 (default: dc1-lineage-gsi)
#   DC1_BRANCH      fork branch                    (default: main)
#   DC1_CLEAN       set 1 to wipe the tree first   (default: reuse)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

BUILD_DIR="${DC1_BUILD_DIR:-$HOME/dc1-build}"
REMOTE="${DC1_GITHUB:-https://github.com/sethforprivacy/}"
REPO="${DC1_REPO:-dc1-lineage-gsi}"
BRANCH="${DC1_BRANCH:-main}"
JOBS=8
VARIANT=user
GAPPS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --userdebug) VARIANT=userdebug ;;
    --with-gapps=*) GAPPS="${1#--with-gapps=}" ;;
    -j) shift; JOBS="$1" ;;
    -h|--help) sed -n '1,30p' "$0" | sed 's/^#\{0,1\} //'; exit 0 ;;
    *) echo "unknown arg: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

# --- 0. local validation (fast, no tree needed) --------------------------
"$HERE/validate-fork.sh"

# --- 1. tree layout --------------------------------------------------------
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"
if [ ! -d .repo ]; then
  repo init -q -u https://github.com/LineageOS/android.git -b lineage-23.2
  git clone -q https://github.com/TrebleDroid/treble_manifest .repo/local_manifests -b android-16.0
  rm -f .repo/local_manifests/replace.xml   # LineageOS build (not plain AOSP)
  cp "$ROOT/local_manifests/dc1.xml" .repo/local_manifests/dc1.xml
  sed -i.bak -e "s|fetch=\"https://github.com/sethforprivacy/\"|fetch=\"$REMOTE\"|" \
             -e "s|name=\"dc1-lineage-gsi\"|name=\"$REPO\"|" \
             -e "s|revision=\"main\"|revision=\"$BRANCH\"|" \
    .repo/local_manifests/dc1.xml
  rm -f .repo/local_manifests/dc1.xml.bak
fi

# Use the local repository checkout for vendor/dc1 so delta edits take effect
# without pushing first (maintainer loop). Disable with DC1_USE_LOCAL=0.
if [ "${DC1_USE_LOCAL:-1}" = "1" ] && [ -d .repo ] && [ -d vendor/dc1/.git ]; then
  git -C vendor/dc1 remote set-url origin "$ROOT"
  git -C vendor/dc1 fetch -q origin
  git -C vendor/dc1 reset -q --hard "origin/$BRANCH"
  echo "vendor/dc1 pinned to local checkout (origin=$ROOT)"
fi

# --- 2. sync ----------------------------------------------------------------
repo sync -c -j"$JOBS" --force-sync --no-tags --no-clone-bundle

# --- 3. TrebleDroid device patches -----------------------------------------
PATCHZ="$BUILD_DIR/patches-for-developers.zip"
if [ ! -f "$PATCHZ" ]; then
  curl -fsSL -o "$PATCHZ" \
    "https://github.com/TrebleDroid/treble_experimentations/releases/latest/download/patches-for-developers.zip"
fi
rm -rf "$BUILD_DIR/.td-patches" && mkdir -p "$BUILD_DIR/.td-patches"
unzip -qo "$PATCHZ" -d "$BUILD_DIR/.td-patches"
for patch in "$BUILD_DIR"/.td-patches/*/0001-*.patch; do
  [ -e "$patch" ] || continue
  proj="$(basename "$(dirname "$patch")")"
  echo ">> TrebleDroid patch -> $proj"
  git -C "$BUILD_DIR/$proj" am --quiet "$patch"
done

# --- 4. generate TrebleDroid products with our fragment ---------------------
( cd "$BUILD_DIR/device/phh/treble" && bash generate.sh vendor/dc1/common.mk )

# --- 5. build -----------------------------------------------------------------
source "$BUILD_DIR/build/envsetup.sh"
lunch "treble_arm64_bvN-$VARIANT"
make -j"$JOBS" systemimage

IMG="$BUILD_DIR/out/target/product/tdgsi_arm64_ab/system.img"
[ -f "$IMG" ] || { echo "system.img not produced (expected $IMG)" >&2; exit 1; }

# --- 6. optional GApps merge ---------------------------------------------------
if [ -n "$GAPPS" ]; then
  if [ "$(id -u)" != 0 ] && ! command -v sudo >/dev/null; then
    echo "--with-gapps requires root for loop-mount (run with sudo or on a root user)" >&2
    exit 1
  fi
  echo "Merging GApps from $GAPPS into system.img …"
  "$HERE/merge-gapps.sh" "$IMG" "$GAPPS" || {
    echo "GApps merge failed; base image is still valid at $IMG" >&2
    exit 1
  }
fi

echo
echo "Build complete:"
ls -lh "$IMG"
echo "Flash: fastboot flash system $IMG   (see docs/flash.md)"
