# Building the DC-1 LineageOS GSI

Standard [TrebleDroid build flow](https://github.com/TrebleDroid/treble_experimentations/wiki/How-to-build-a-GSI%3F)
plus our `vendor/dc1` fragment (inherited via `generate.sh`). The delta
adds **zero patches to upstream trees** — `common.mk` is included as a
product fragment, exactly as the build system intends.

## Prerequisites

- 64-bit Linux (or macOS for everything *except* the final build; the ROM
  itself is built on Linux in practice), 16 GB+ RAM, ~150 GB free disk,
  `repo`, JDK 17, **git-lfs** (required by the Chromium WebView prebuilt
  repos — a sync without it fails with
  `Cannot initialize work tree … git-lfs filter-process: not found`),
  usual AOSP build deps (see the wiki link above).
- `tools/build-release.sh` automates steps 1–7 below; the manual flow is
  spelled out here for understanding.

## Manual build

```bash
# 1) Tree layout
mkdir -p ~/dc1-build && cd ~/dc1-build

# 2) LineageOS 23.2 (Android 16) manifest — current base
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2

# 3) TrebleDroid local manifests (device/phh/treble, patches glue, vndk…)
git clone https://github.com/TrebleDroid/treble_manifest .repo/local_manifests -b android-16.0
# LineageOS (not AOSP) build → drop the AOSP-replacement include:
rm .repo/local_manifests/replace.xml

# 4) Add our fork as vendor/dc1
cat > .repo/local_manifests/dc1.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote name="dc1" fetch="https://github.com/sethforprivacy/" />
  <project path="vendor/dc1" name="dc1-lineage-gsi" remote="dc1" revision="main" />
</manifest>
EOF

# 5) Sync
repo sync -c -j$(nproc --ignore=2) --force-sync --no-tags --no-clone-bundle

# 6) TrebleDroid device patches (fixes for non-AOSP quirks across devices incl. MediaTek)
cd /tmp
wget -q https://github.com/TrebleDroid/treble_experimentations/releases/latest/download/patches-for-developers.zip
unzip -qo patches-for-developers.zip
# Each <project>/0001-*.patch applies to that project's tree:
#   git -C <top>/<project> am /tmp/patches-for-developers/<project>/*.patch
# (tools/build-release.sh does this loop for you.)

# 7) Generate TrebleDroid product files with OUR fragment as the ROM base
cd ~/dc1-build/device/phh/treble && bash generate.sh vendor/dc1/common.mk && cd -

# 8) Build — vanilla, unrooted (no-su), A/B, arm64
source build/envsetup.sh
lunch treble_arm64_bvN-user
make -j$(nproc --ignore=2) systemimage

# 9) Artifact
ls -lh out/target/product/tdgsi_arm64_ab/system.img
```

Notes:

- `-user` (not `-userdebug`) ⇒ `ro.debuggable=0`, no `adb root`, no su —
  the "unrooted" property of the fork. Flashing is done the same way as any
  GSI; the first boot does not grant root.
- If you need `adb root` for amber-node discovery **before** locking the
  configured node, build `treble_arm64_bvN-userdebug` once for diagnostics —
  do **not** ship it as the release build.
- The lunch product reference (`.repo/local_manifests/dc1.xml`) maps this
  whole repo to `vendor/dc1`; inert repo content (`docs/`, `tools/`, …) is
  ignored by the build.

## Output check

`systrace`-free sanity checks after boot (via adb):

```bash
adb shell getprop ro.build.version.release          # 16
adb shell getprop ro.build.version.security_patch    # current ASB month
adb shell getprop ro.product.device                 # jagar (from vendor)
adb shell settings get system screen_brightness_amber_rate  # 1023 (default)
adb shell cmd uimode night yes                      # grayscale path present
```

The amber app logs under `adb logcat -s AmberControl` and shows its chosen
node on first sync.

## Keeping up to date

1. `repo sync` picks up the new LineageOS branch state.
2. Re-download and re-apply TrebleDroid patches (step 6).
3. Re-run `generate.sh vendor/dc1/common.mk` (step 7) — this is the entire
   "rebase" of our delta, because the delta lives in this repo, not in
   upstream trees.
4. Rebuild, smoke-test, release.

CI (`.github/workflows/upstream.yml`) runs steps 2–3's *validation* on every
upstream release and files an Issue when a rebuild is warranted — see
`docs/ci.md` workflow doc included in `.github/workflows/`.
