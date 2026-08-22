# Building the DC-1 LineageOS GSI

We build the **proven LineageOS-GSI composition** used by the maintained
`LineageOS 23.2` GSI releases:

- `LineageOS/android` **`lineage-23.2`** (Android 16 base)
- `MisterZtr/treble_manifest` **`lineage-23.2`** — LOS-tuned local manifests
  (TrebleDroid `device_phh_treble` + `vendor_interfaces` etc., but *not* the
  AOSP-only v28/v29/v30 handling; he uses `naz664/prebuilts_vndk_v28` so the
  VNDK modules don't collide with LineageOS's own `hardware/lineage/compat`)
- `MisterZtr/LineageOS_gsi` **`lineage-23.2`** — his "apply-patches.sh" patch
  layers (trebledroid / trebledroid-staging / personal) that adapt the tree
  for LineageOS and define the `lineage_arm64_bvN4` (ext4) / `bvNE` (erofs)
  products.
- `vendor/dc1` — **our delta**, injected by one patch:
  `patches/device_phh_treble__0001-dc1-include-vendor-dc1.patch` appends
  `$(call inherit-product, vendor/dc1/common.mk)` to
  `lineage_arm64_bvN4.mk`.

Why not raw TrebleDroid `generate.sh` + `patches-for-developers.zip`? That kit
is AOSP-targeted and largely does **not** apply to LineageOS trees (verified
2026-08-19: ~204/205 patches unapplicable; TD's `vendor_interfaces` expects
legacy `android.hardware.*@1.0` interfaces that LineageOS's
`hardware/interfaces` fork dropped; TD's v28 prebuilt collides with
`hardware/lineage/compat`). The maintained LOS-GSI builders converged on this
composition instead — we follow them to keep "minimal, maintained, tracks
upstream".

Our delta remains: `common.mk` (amber app + sepolicy + props + overlays),
`AmberControl/`, `sepolicy/`, `privapp-permissions-dc1.xml`,
`dc1-excluded-hardware.xml` (masks the camera/light-sensor/telephony features
the stock `/vendor` wrongly declares — the telephony mask is also what makes
the setup wizard's SIM screen self-skip), `rro/` (framework config RRO, which
also carries the `overrides:` list that drops the camera apps from the
image), `local_manifests/dc1.xml`, plus any `patches/*.patch` applied to
upstream trees by `patches/apply.sh`.

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

# 2) LineageOS 23.2 (Android 16) manifest — current base (--git-lfs for the WebView prebuilts)
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs

# 3) LOS-tuned local manifests (MisterZtr's lineage-23.2 branch)
git clone https://github.com/MisterZtr/treble_manifest.git .repo/local_manifests -b lineage-23.2

# 4) Add our fork as vendor/dc1 (snake/repo naming as in local_manifests/dc1.xml)
cp /path/to/dc1-lineage-gsi/local_manifests/dc1.xml .repo/local_manifests/dc1.xml

# 5) Sync
repo sync -c -j$(nproc --ignore=2) --force-sync --no-tags --no-clone-bundle

# 6) MisterZtr lineage-GSI patch layers (commits his products/interface adaptions)
bash LineageOS_gsi/patches/apply-patches.sh .
# A handful of patches may print "FAILED APPLYING" — benign device quirks; the
# tree still builds (see the live run notes in the fork README/issues).

# 7) Our delta: one patch appends vendor/dc1/common.mk to lineage_arm64_bvN4.mk
cd device/phh/treble && git am /path/to/dc1-lineage-gsi/patches/device_phh_treble__0001-dc1-include-vendor-dc1.patch && cd -

# 8) TrebleApp prebuilt (gradle, bundled SDK, platform-signed) — REQUIRED by the build
cd treble_app && bash build.sh && test -f TrebleApp.apk && cd -

# 9) Build — vanilla, unrooted (user), ext4, A/B, arm64
source build/envsetup.sh
ccache -M 50G
lunch lineage_arm64_bvN4 bp4a user
make -j$(nproc --ignore=2) systemimage

# 10) Artifact
ls -lh out/target/product/tdgsi_arm64_ab/system.img
```

Notes:

- `user` (not `userdebug`) ⇒ `ro.debuggable=0`, no `adb root`, no su — the
  "unrooted" property of the fork. (`breakfast lineage_arm64_bvN4-bp4a-userdebug`
  is MisterZtr's equivalent for a debugging build; don't ship it.)
- `bp4a` is the A16 release name this branch expects; the space-separated
  `lunch <product> <release> <variant>` form is required since A16 lunch
  splits on `-` (`lineage_arm64_bvN4-user` style combos are rejected).
- `.repo/local_manifests/dc1.xml` maps this whole repo to `vendor/dc1`; inert
  repo content (`docs/`, `tools/`, …) is ignored by the build.

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

1. `repo sync` picks up new LineageOS + MisterZtr treble_manifest state.
2. Re-run `apply-patches.sh` (step 6) — applies/commits his latest layers on
   the fresh tree.
3. Re-apply our include patch + rebuild TrebleApp (steps 7–8). **That is the
   entire "rebase" of our delta** — the delta lives in this repo, not in
   upstream trees, so rebasing is a couple of git-am commands.
4. Rebuild, smoke-test, release.

CI (`.github/workflows/upstream.yml`) runs daily: it detects new upstream
releases (LineageOS GSI + TrebleDroid patches), validates that our delta
(patch apply + fragment contract) still fits current upstream, and files or
updates the tracking Issue.
