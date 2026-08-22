#!/usr/bin/env bash
#
# build-release.sh — one-command build of the DC-1 LineageOS GSI.
#
# Implements the proven MisterZtr lineage-GSI composition on any Linux host:
#   repo init LineageOS lineage-23.2 (--git-lfs)
#   + MisterZtr/treble_manifest lineage-23.2 as local manifests
#   + our fork as vendor/dc1 (local_manifests/dc1.xml)
#   + apply-patches.sh (his 3 patch layers)
#   + our DC-1 delta patch (vendor/dc1 include in lineage_arm64_bvN4.mk)
#   + TrebleApp prebuilt (gradle, bundled SDK, platform-signed)
#   + permissive-domain strip (unrooted user builds forbid them)
#   + lunch lineage_arm64_bvN4 bp4a user  &&  make systemimage
#
# Requirements: 16GB+ RAM (32 recommended), ~350GB disk, JDK17, git-lfs,
# ccache, repo, standard AOSP build packages. See docs/build.md.
#
# Usage:
#   ./build-release.sh                      # unrooted `user` build
#   ./build-release.sh --userdebug          # debugging build (don't ship)
#   ./build-release.sh -j 24                # parallel jobs
#
# Env overrides:
#   DC1_BUILD_DIR   build tree location     (default: ~/dc1-build)
#   DC1_GITHUB      git remote org url      (default: https://github.com/sethforprivacy/)
#   DC1_REPO        fork repo name          (default: dc1-lineage-gsi)
#   DC1_BRANCH      fork branch             (default: main)
#   DC1_JOBS        parallel jobs           (default: nproc, cap 24)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

BUILD_DIR="${DC1_BUILD_DIR:-$HOME/dc1-build}"
REMOTE="${DC1_GITHUB:-https://github.com/sethforprivacy/}"
REPO="${DC1_REPO:-dc1-lineage-gsi}"
BRANCH="${DC1_BRANCH:-main}"
JOBS="${DC1_JOBS:-$(nproc)}"
[ "$JOBS" -gt 24 ] && JOBS=24
VARIANT=user
GAPPS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --userdebug) VARIANT=userdebug ;;
    --with-gapps=*) GAPPS="${1#--with-gapps=}" ;;
    -j) shift; JOBS="$1" ;;
    -h|--help) sed -n '2,30p' "$0" | sed 's/^#\{0,1\} \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

# --- 0. local validation (fast) ------------------------------------------
"$HERE/validate-fork.sh"

# --- 1. tree --------------------------------------------------------------
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"
if [ ! -d .repo ]; then
  repo init -q -u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs
  git clone -q https://github.com/MisterZtr/treble_manifest.git .repo/local_manifests -b lineage-23.2
  rm -f .repo/local_manifests/README.md
  curl -fsSL "$REMOTE$REPO/$BRANCH/local_manifests/dc1.xml" \
    -o .repo/local_manifests/dc1.xml 2>/dev/null || cp "$ROOT/local_manifests/dc1.xml" .repo/local_manifests/dc1.xml
fi

# keep vendor/dc1 pinned to OUR checkout so local edits are used directly;
# disable with DC1_USE_LOCAL=0 (e.g. to build pristine upstream main).
if [ "${DC1_USE_LOCAL:-1}" = "1" ] && [ -d vendor/dc1/.git ]; then
  git -C vendor/dc1 remote remove origin 2>/dev/null || true
  git -C vendor/dc1 remote add origin "$ROOT"
fi

# --- 2. sync ---------------------------------------------------------------
repo sync -d -c -j"$JOBS" --force-sync --no-tags --no-clone-bundle --prune
[ "${DC1_USE_LOCAL:-1}" = "1" ] && [ -d vendor/dc1/.git ] && {
  git -C vendor/dc1 remote set-url origin "$ROOT"; git -C vendor/dc1 fetch -q origin
  git -C vendor/dc1 reset -q --hard "origin/$BRANCH"
}

# --- 3. MisterZtr patch layers ---------------------------------------------
( cd "$BUILD_DIR" && bash LineageOS_gsi/patches/apply-patches.sh ) || {
  echo "apply-patches.sh failed — resolve manually, then re-run make" >&2; exit 1; }

# --- 4. our DC-1 delta patch (include vendor/dc1 in bvN4 product) ----------
P="$ROOT/patches/device_phh_treble__0001-dc1-include-vendor-dc1.patch"
if ! grep -q "vendor/dc1" "$BUILD_DIR/device/phh/treble/lineage_arm64_bvN4.mk" 2>/dev/null; then
  git -C "$BUILD_DIR/device/phh/treble" am --quiet --3way "$P" 2>/dev/null \
    || git -C "$BUILD_DIR/device/phh/treble" apply --3way --index "$P" \
    || { echo "DC1 delta patch failed"; exit 1; }
  echo ">> DC-1 include patch applied"
else
  echo ">> DC-1 include already present in product mk"
fi

# --- 5. permissive strip (user builds forbid permissive domains) -----------
STRIPPED=0
for f in $(grep -rl "^[[:space:]]*permissive " \
    "$BUILD_DIR/device/phh/treble/sepolicy" \
    "$BUILD_DIR/device/lineage/sepolicy" 2>/dev/null); do
  sed -i -e "/^[[:space:]]*permissive [a-z_0-9]*;/d" "$f"; STRIPPED=1
done
[ "$STRIPPED" = 1 ] && echo ">> stripped permissive domains for $VARIANT build"

# --- 6. TrebleApp prebuilt ---------------------------------------------------
if [ ! -f "$BUILD_DIR/treble_app/TrebleApp.apk" ]; then
  echo ">> building TrebleApp prebuilt"
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$PATH"
  ( cd "$BUILD_DIR/treble_app" && bash build.sh >/dev/null 2>&1 )
  [ -f "$BUILD_DIR/treble_app/TrebleApp.apk" ] || { echo "TrebleApp build failed"; exit 1; }
fi

# --- 7. lunch + make ---------------------------------------------------------
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/mnt/storage/bin:$PATH"
export USE_CCACHE=1 CCACHE_COMPRESS=1 CCACHE_MAXSIZE=100G
export CCACHE_DIR="${CCACHE_DIR:-$HOME/dc1-ccache}"
export LC_ALL=C
ccache -M 100G >/dev/null 2>&1 || true
set +u
source "$BUILD_DIR/build/envsetup.sh"
lunch lineage_arm64_bvN4 bp4a "$VARIANT"
make -j"$JOBS" systemimage

IMG="$BUILD_DIR/out/target/product/generic_arm64/system.img"
echo
echo "=== BUILD DONE rc=$? $(date -u) ==="
ls -lh "$IMG"

# --- 8. optional GApps merge --------------------------------------------------
if [ -n "$GAPPS" ]; then
  echo "NOTE: MindTheGapps merge requires root loop-mount; see tools/merge-gapps.sh" >&2
fi
