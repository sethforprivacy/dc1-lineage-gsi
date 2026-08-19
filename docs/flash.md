# Flashing the DC-1 LineageOS GSI

Two safe phases. Start with DSU (no unlock, no root, reversible) to sanity
check the image; only then unlock and flash for permanence.

> **Back up first.** A GSI flash replaces the system partition. The stock
> image can be restored via Daylight's tooling / an OTA zip — have one at
> hand before unlocking. See daylighthacker.wiki for stock assets.

## Phase 0 — verify the image with DSU (recommended first step)

DSU lets you *test-boot* a GSI from Android without unlocking the bootloader
or touching partitions. The community's preferred tool for the DC-1 is
[DSU Sideloader](https://github.com/VegaBobo/DSU-Sideloader) (the
daylighthacker.wiki GSI list points to it as the standard path).

1. Enable Developer options (tap Build number 7×), enable **USB debugging**.
2. Install DSU Sideloader, grant it the DSU writer permission.
3. Point it at the built `system.img`, confirm the DSU slot, reboot.
4. You boot into the GSI. Test: display, touch, wifi, the **Amber QS tile**
   (`AmberControl`), grayscale, refresh presets.
5. Reboot to stock (DSU automatically discards or you delete the DSU slot).

Bugs found at this stage are free — iterate on the delta (docs/amber.md) and
rebuild without touching your stock setup.

## Phase 1 — permanent install (unlock + flash)

A custom GSI requires an **unlocked bootloader** (this unit ships locked:
`ro.boot.vbmeta.device_state=locked`, verified boot `green`). Unlocking wipes
data, is usually one-way-per-firmware, and (on some MediaTek configs) needs
the vendor's flashing tool. This is the only step that can't be done from
this repo's docs generically; current unlock/flash specifics live with the
DC-1 community (daylighthacker.wiki) — the flow below is the standard one.

```bash
# 0) On the device: Developer options → OEM unlocking.

# 1) Boot into the bootloader, unlock
adb reboot bootloader
fastboot flashing unlock            # confirm on screen; wipes data

# 2) Boot fastbootd (userspace fastboot for dynamic partitions)
fastboot reboot fastboot
# 3) Disable dm-verity/vbmeta verification so the unsigned GSI boots
fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
# 4) Flash the GSI to system (A/B handled automatically)
fastboot flash system system.img
fastboot reboot
```

Expected result: LineageOS 23.2 boots, unrooted, amber on by default at full
(`screen_brightness_amber_rate=1023`), Play Services absent until Phase 2.

If the unit refuses to boot a GSI even with vbmeta disabled, the
`ro.boot.product.hardware.sku`/`ro.boot.*` gates may need a specific vbmeta /
davinci keyword; capture `fastboot getvar all` and check the wiki/community
before flashing further.

## Phase 2 — Google Play Services (MindTheGapps)

The fork ships **without** GMS by design. Flash MindTheGapps separately —
it is the source-available set recommended by LineageOS:

1. Download the MindTheGapps zip matching the Android version
   (`MindTheGapps-16.x-arm64` from the LineageOS wiki / their GitHub
   releases).
2. Flash from a recovery that supports adb sideload
   (`adb reboot recovery` → `Apply update from ADB` →
   `adb sideload MindTheGapps-…-arm64.zip`), or
   boot the GSI once, then use a holders/repack flow (see below).

If no working recovery exists for the DC-1 yet, use the repack path included
in `tools/build-release.sh` (`--with-gapps=path/to/MindTheGapps.zip`): it
un-sparses `system.img`, merges the GApps APKs/libs into the image, and
re-sparses — one artifact, flashed with the same `fastboot flash system`.

> Play Services caveat: Play Protect shows *uncertified* on this device (any
> non-Google build). Play Store and nearly all apps work; strictest
> Play-Integrity-only apps may refuse. See docs/rom-choice.md.

## Get back to stock

Reboot to fastbootd and restore the stock `system` (and `vbmeta`) images you
backed up in the beginning; or use Daylight's OTA tooling
(adiktofsugar/daylight) to re-image the unit.
