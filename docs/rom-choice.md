# Why LineageOS GSI (and what was rejected)

Decision made 2026-08-19 against these requirements:

1. Google Play Services must run well.
2. Underlying ROM must have a strong track record of keeping up with AOSP
   security bulletins (monthly ASB cadence matters).
3. The fork must be easy to maintain: minimal delta on top, re-basable every
   month against upstream.
4. Unrooted; amber frontlight control baked into the ROM.

## Chosen: LineageOS GSI (TrebleDroid base)

- **Security cadence** — LineageOS merges the Android Security Bulletin into
  its release branches monthly, on schedule, for years. This is the strongest
  ASB record in the ecosystem and the deciding factor.
- **Maintainability** — the build is the standard public TrebleDroid flow
  (`repo init` LineageOS, `treble_manifest`, TrebleDroid patches, lunch
  `treble_arm64_bvN`). Our delta is a single product fragment (`common.mk`)
  plus our own `AmberControl` app — **zero patches to upstream trees**, so a
  monthly rebase is trivial and CI can validate it automatically.
- **Device fit** — the DC-1 is a MediaTek MSSI device; that is exactly the
  class TrebleDroid GSIs are built and tested against.
- **Unrooted** — built as `treble_arm64_bvN` (vanilla, no-su), enforcing
  SELinux, with the amber app running in the `system_app` domain under a
  narrow LED-write policy.
- **Play Services** — GMS runs fine via MindTheGapps flashed after the image
  (docs/flash.md). This keeps the ROM itself GMS-free (small, clean, easier
  to maintain) while giving full Play Services on-device.

### Known trade-off

Play Protect shows the device as **uncertified** (true of every non-Google
ROM; Google re-certifies only their own GSI). Play Store, banking, and the
vast majority of apps work; apps with strict Play Integrity *hardware*
checks (e.g. Google Wallet contactless) may refuse. If that is unacceptable,
the alternative is the Google official GSI — see below.

## Alternatives considered and why not chosen

### Google official GSI (GMS baked in, Play Protect certified)

The only base that ships GMS with Play Protect certification out of the box.

- **Rejected on cadence:** images are released per QPR/beta milestone, not
  monthly; the newest ones are labeled experimental (`-exp`). LineageOS ships
  a newer ASB more reliably and more promptly.
- **Rejected on maintainability:** it is bare AOSP with a beta release
  treadmill; there is no community patchset to inherit, and "keep up to date
  with upstream" means tracking Google's role-out schedule.
- **Rejected on feature fit:** no LiveDisplay-style tuning, no ecosystem
  fixes for MediaTek quirks.

Good choice if Play Protect certification is a hard requirement; not chosen
here because the user's priority list puts security-update cadence and
maintainability first.

### AOSP / phh GSI (TrebleDroid)

Monthly-tracking, well-maintained, but:
- no GMS at all (same MindTheGapps story as LineageOS, without LineageOS's
  feature set);
- phh's active builds are now Lineage-derived; bare AOSP GSI is the
  less-maintained half and adds no benefit over LineageOS.

### GMS-baking hobby GSIs (Evolution X, PixelOS, crDroid, and the rest)

Some ship GMS in-image and even certify. Rejected across the board:

- patch cadence is irregular and several lag months behind ASB;
- several are closed-source (cannot fork, cannot run CI, cannot validate);
- dependency on a single volunteer maintainer conflicts with the "easily
  maintained fork" requirement.

## Verification targets pinned in this repo

- Base manifest branch: `LineageOS/android` **`lineage-23.2`**
- TrebleDroid device: `github.com/TrebleDroid/device_phh_treble` (HEAD)
- TrebleDroid local manifests: `github.com/TrebleDroid/treble_manifest` **`android-16.0`**
- TrebleDroid patches: `releases/latest/download/patches-for-developers.zip`
- GSI variant reference: `MisterZtr/LineageOS_gsi` (latest published LineageOS
  GSI release; used as the "upstream release" signal in CI)
