# Root walkthrough (for amber-node discovery only)

The fork is unrooted by design. Root is needed for exactly **one** task:
identifying the exact kernel LED node the amber frontlight uses, so the
ROM's `AmberControl` app can be pointed at it deterministically
(`ro.dc1.amber.node`) instead of relying on first-boot auto-discovery.

> With auto-discovery in place you may not need this at all — it usually
> guesses right. Pin only if discovery falls back to the wrong node.

Current device state (checked 2026-08-19): **bootloader locked**
(`ro.boot.vbmeta.device_state=locked`, verified boot `green`), hence no root
on stock today. Full unlock → root → flash is the standing path.

## When you'll want this

After the first GSI boot, check the amber app's choice:

```bash
adb logcat -s AmberControl          # look for "amber node discovered: …"
```

If it chose the white backlight or nothing (some MediaTek builds expose many
LED nodes), fall back to discovery.

## Walkthrough

### 1. Unlock the bootloader (data wipe; one-time)

1. On the DC-1: **Settings → Developer options → USB debugging**, and enable
   **OEM unlocking** if offered.
2. Connect adb, then:
   ```bash
   adb reboot bootloader
   fastboot flashing unlock          # confirm on the screen
   ```
3. This wipes /data and opens the device to custom images. Stock returns via
   the restore procedure in `docs/flash.md`.

### 2. Get root temporarily (diagnostics only)

Two options — pick whichever works on the DC-1's recovery situation:

- **userdebug GSI build of our own fork** (recommended; you're already set up
  to build):
  ```bash
  tools/build-release.sh --userdebug
  fastboot flash system out/target/product/tdgsi_arm64_ab/system.img
  ```
  `userdebug` grants `adb root` — no Magisk needed.
- **Magisk on stock/GSI:** flash Magisk via patched boot image, then
  `adb shell su` works.

### 3. Find the amber node (as root)

The repo ships the original interactive finder — run it *on the device as
root*; it flashes each LED node and you confirm which one is amber:

```bash
adb push amber-root/discover-amber-node.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/discover-amber-node.sh'
# → writes /data/adb/amberd/config with AMBER_NODE + AMBER_NODE_MAX
```

To avoid guessing entirely, cross-check the node the app auto-picked:

```bash
adb shell su -c 'ls -l /sys/class/leds/*/brightness'
adb shell su -c 'cat /sys/class/leds/*/max_brightness'
```

### 4. Pin the node into the ROM and rebuild

```bash
# in this repo
echo "ro.dc1.amber.node=/sys/class/leds/<NODE>/brightness" >> /tmp/dc1-node.prop
# …then add the line to common.mk:
#   PRODUCT_SYSTEM_DEFAULT_PROPERTIES += ro.dc1.amber.node=/sys/class/leds/<NODE>/brightness
tools/build-release.sh                # unrooted `user` build again
```

(You can also test without rebuilding: `adb shell setprop ro.dc1.amber.node
…` on a rooted session — the app reads it on next sync.)

### 5. Remove root

The release build is `-user`: no `adb root`, no su, enforcing SELinux. After
pinning the node, ship only the unrooted image. If you booted a userdebug
build for discovery, re-flash the `user` image over it — nothing else to
undo (the discovery only wrote `/data/adb/amberd/config`, which a factory
reset or `rm -rf /data/adb/amberd` clears).
