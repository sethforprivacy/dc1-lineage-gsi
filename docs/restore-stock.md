# Restoring the DC-1 to stock (SP Flash Tool)

Use this to go back to stock Android 13 from the GSI — the entry point for
Daylight's own OTA path (`0.9.9.58` → `1.0.18` → Android 16). Daylight has no
tested way to flash Android 16 directly onto a re-ROM'd unit, so a stock
restore first is required, not optional: it is what re-provisions the tablet on
their update server.

## The one destructive mistake: Format All

SP Flash Tool has four operations. **Only `download` is safe here.**

| Operation | CLI | What it does on this image |
|---|---|---|
| Download Only | `-c download` | Writes the partitions flagged `is_download` in the scatter |
| Format All + Download | `-c format-download` | Erases the **whole chip**, then downloads |
| Format | `-c format` | Erases the whole chip |
| Firmware Upgrade | `-c firmware-upgrade` | Download with backup/restore of a few partitions |

The DC-1 image carries **no `nvram`, `nvdata`, `proinfo`, `protect1/2`,
`seccfg` or `otp` payload** — every one of those is `is_download=false` in
`MT6789_Android_scatter.xml`, and the zip has no matching `.img` file. A
whole-chip erase therefore deletes the tablet serial number, WiFi/Bluetooth MAC
and IMEI-derived state with nothing to restore them from. Download Only never
touches those partitions. There is no way back from Format All without a
vendor-side provisioning file.

## What you need

- **An x86_64 Linux host** — MediaTek ships no macOS build of SP Flash Tool, and
  a VM with USB passthrough is not a reliable BROM target. Validated on
  Debian 12 / PureOS 11, kernel 6.1.
- The stock image:
  `20260210.1745.1407.jagar.user-refs.tags.0.9.9.58.prod.2-signed-image.zip`
  (3,172,309,193 bytes) from Daylight's flashing page.
- `SP_Flash_Tool_v6.2228_Linux.zip`.
- USB data cable; tablet charged (flashing runs on the tablet's battery).

## Preconditions on the host

```bash
# MediaTek BROM/USB access is root-only
sudo systemctl stop ModemManager            # it otherwise claims the preloader port
sudo install -m644 99-ttyacms.rules /etc/udev/rules.d/   # ID_MM_DEVICE_IGNORE for vendor 0e8d
sudo udevadm control --reload-rules
```

The image has no `scatter_checksum.xml`, so SP Flash Tool skips its own
checksum pass. Verify the download instead: `unzip -t firmware.zip`.

## Layout after extracting the image

```
20260210.1745.1407.jagar.user-image/
├── MT6789_Android_scatter.xml          partition table + is_download flags
├── super.img                           4.7 GB — system/vendor/product
├── userdata.img                        blank userdata (this is the wipe)
├── boot.img, vendor_boot.img, vbmeta*.img, lk.img, preloader_jagar.bin, …
└── download_agent/
    ├── flash_signedDA.xml              pass to -f
    ├── authsv5_jagar.auth              pass to -a
    └── DA_BR_jagar.bin                 download agent (referenced by the XML)
```

`flash_signedDA.xml` holds only the project/DA/scatter pointers — the partition
list and the per-partition `is_download` flags come from the scatter.

## Flash (console mode — no GUI, no clicking)

SP Flash Tool v6 has a scriptable console mode; `-c download` is exactly the
GUI's "Download Only" dropdown entry. `-t with` = tablet connected with
battery.

```bash
cd SP_Flash_Tool_v6.*_Linux
sudo ./SPFlashToolV6 \
  -f  ../fw/*.jagar.user-image/download_agent/flash_signedDA.xml \
  -a  ../fw/*.jagar.user-image/download_agent/authsv5_jagar.auth \
  -c  download -t with
```

Order matters: **start the tool first**, then power the tablet fully off and
plug in USB. The tool scans for a BROM/preloader device (it shows up as
`0e8d:2000 MediaTek MT65xx Preloader`) and takes it from there. A successful
run ends with the download reaching 100% and the unit rebooting (hold the
top-right power button if it stays dark until the Daylight splash).

[`tools/flash-stock.sh`](../tools/flash-stock.sh) wraps the above — host
preconditions, path discovery, root check and a timestamped log:

```bash
sudo DC1_STOCK=~/dc1-stock tools/flash-stock.sh
```

It expects `$DC1_STOCK/fw/<build>.jagar.user-image/` and
`$DC1_STOCK/spflash/SP_Flash_Tool_v6.*_Linux/`.

## What survives, what does not

| | Effect |
|---|---|
| Serial, WiFi/BT MAC, IMEI state (nvram/nvdata/proinfo/protect*) | **Preserved** — not downloaded |
| `/sdcard` (notes, PDFs, downloads, screenshots) | **Wiped** — `userdata` is downloaded blank |
| Apps and app data | **Wiped** |
| Bootloader lock state (`seccfg`) | Untouched — a unit unlocked for the GSI **stays unlocked** |

Back up `/sdcard` first if you want anything off it (`adb pull /sdcard`, or
Daylight Notes' own export) — Download Only writes `userdata` blank, so the
tablet's contents go with it. Do not try to preserve `userdata` by unchecking
it: coming from the GSI the data is Lineage's, and stock Android hits the same
provider-schema downgrade that `docs/flash.md` documents (crash-looping
`android.process.media`) even when decryption succeeds. If Daylight's updater
ever refuses with the bootloader unlocked, `fastboot flashing lock` on the
booted stock build re-locks it — re-locking wipes data again.

## First boot may land in recovery — that is expected

On the first boot after the restore the unit can stop in Android recovery with
*"Can't load Android system. Your data may be corrupt"* and the options **Try
again** / **Factory data reset**. Tap **Factory data reset**.

Cause: Download Only writes a blank `userdata.img` but `metadata` is
`is_download=false`, so the previous ROM's file-based-encryption keys are still
on the tablet. Stock cannot initialise against them, and "Try again" re-runs the
same boot against the same keys, so it loops. Recovery's factory reset formats
`userdata` **and** `metadata` together, which is the pairing stock needs. This
does not touch nvram — the serial number survives it.

Vendor-side, nothing about this is visible: the flash itself reported
`CMD:WRITE-PARTITIONS command execute successed` / `All command exec done!`.

## Verified end state on a real unit

```
ro.build.fingerprint   Daylight/vext_jagar/jagar:13/TP1A.220624.014/2602101745:user/release-keys
ro.build.display.id    20260210.1745.user
ro.boot.verifiedbootstate  green
ro.boot.vbmeta.device_state  locked
ro.boot.flash.locked   1
ro.crypto.state        encrypted (file)
```

The unit comes back **locked and green without any extra step**: the stock
signed `vbmeta`/`vbmeta_system`/`vbmeta_vendor` images (all `SHA256_RSA2048`,
`Flags: 0` — verity and verification enabled) replace the GSI's
`--disable-verity --disable-verification` vbmeta, and `seccfg` is never
rewritten. No `fastboot flashing lock` is needed, and none should be attempted
while the chain is not yet known-good: it re-wipes `/data`.

The updater is `com.ota.manager` (**Software Updates**).

## Then hand it to Daylight's OTA path

1. **Charger in.** The A/B install needs several GB of headroom and Android
   refuses to apply an OTA on a low battery; this unit booted at 12%.
2. Wi-Fi on, open **Software Updates** → Update → `1.0.18` (the build that
   carries the new updater). Large download; their server may need a few
   minutes of connectivity before the update is offered — a unit that has been
   offline since August is not registered for the rollout yet.
3. Tell Daylight it is on `1.0.18`; they move it to Android 16 and it arrives
   as an "Update available" notification.
