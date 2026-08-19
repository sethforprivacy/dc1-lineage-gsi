# DC-1 LineageOS GSI

A maintained, unrooted **LineageOS 23.2 (Android 16) GSI** for the Daylight
DC-1 tablet ("Paper" display, MediaTek MT8781 / Helio G99, A/B + dynamic
partitions, VNDK 31).

This is a *fork*, not a from-scratch ROM: it is the standard
[TrebleDroid](https://github.com/TrebleDroid/treble_experimentations)
LineageOS GSI build with a small DC-1 delta glued on top via a single product
fragment (`common.mk`). Upstream is re-based on every LineageOS/TrebleDroid
release — CI detects the release, validates the delta still applies, and
opens an Issue so the image can be rebuilt and uploaded.

## Why LineageOS GSI

- **Security updates:** LineageOS merges the AOSP Security Bulletin monthly,
  on schedule — the strongest ASB track record in the Android ecosystem.
- **GSI fit:** built from public sources with the documented TrebleDroid flow;
  exactly the device class (MediaTek MSSI) TrebleDroid targets.
- **Unrooted:** built as `treble_arm64_bvN` — no `su`, no admins.
- **Play Services:** pure image; flash MindTheGapps separately after the GSI
  (see `docs/flash.md`). Play Store and most apps work; Play Protect shows
  "uncertified", as on any non-Google build.

Full rationale: [`docs/rom-choice.md`](docs/rom-choice.md).

## The DC-1 delta (what this fork adds)

| Piece | What | Where |
|---|---|---|
| Product fragment | inherits `vendor/lineage/config/common_full_phone.mk`, adds our packages/sepolicy/props | `common.mk` |
| Amber frontlight | rootless system app: Quick-Settings tile + slider, mirrors `screen_brightness_amber_rate` → kernel LED (auto-discovers the node; override via `ro.dc1.amber.node`) | `AmberControl/` |
| SELinux | lets the amber app read/write `sysfs_leds` under enforcing policy | `sepolicy/dc1amber.te` |
| Priv-app permission | allows `WRITE_SETTINGS` to the amber app | `privapp-permissions-dc1.xml` |
| Repo manifest | adds `vendor/dc1` (this repo) to the build tree | `local_manifests/dc1.xml` |

Everything upstream (LineageOS, TrebleDroid patches, `device/phh/treble`) is
left untouched; the delta is only the files above, plumbed in through
`generate.sh vendor/dc1/common.mk` — no patches to upstream trees.

## Layout

```
common.mk                  Product fragment (sycned into the tree as vendor/dc1/common.mk)
AmberControl/              Rootless amber frontlight app (Java, platform-priv-app)
sepolicy/                  SELinux additions for the amber app
privapp-permissions-dc1.xml
local_manifests/           repo manifest snippet for the build tree
patches/                   Future direct-to-upstream patches (currently: none needed)
tools/                     CI + local scripts (detect, validate, build)
docs/                      hardware, ROM choice, build, flash, amber details
papermode-quick/           No-root paper-mode tweaks for stock (adb, no flash)
amber-root/                Legacy root tooling (amber LED discovery + Magisk bridge pre-GSI)
PaperMode-app/             No-root Android app (grayscale/refresh/warm overlay)
.github/workflows/         CI: upstream detection + validation + Issue notify
```

## Current status

- [x] Device/toolchain verified (connected via adb, stock A13, bootloader locked)
- [x] Base decided: LineageOS 23.2 GSI (TrebleDroid), unrooted, vanilla (no GApps in-image)
- [x] Fork delta authored (amber app, sepolicy, fragment, CI)
- [ ] First build and on-device validation (needs bootloader unlock — see `docs/flash.md`)
- [ ] Amber node auto-discovery validated on-device; `ro.dc1.amber.node` locked in if needed

## Getting involved / building

See [`docs/build.md`](docs/build.md) (build from source) and
[`docs/flash.md`](docs/flash.md) (unlock + flash + GApps). The CI workflow
(`.github/workflows/upstream.yml`) runs daily: detects upstream releases,
validates the delta, and files an Issue titled `Upstream update detected` with
the ready-to-build command when a re-build is warranted.

## License

Apache-2.0 for this repo's own files. The resulting ROM contains upstream
LineageOS/TrebleDroid code under their respective licenses (mostly Apache-2.0
and GPL-2.0/3.0 where applicable).
