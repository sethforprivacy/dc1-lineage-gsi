# Getting a copy of stock (Sol:OS)

`README.md` and `docs/flash.md` both say to keep a copy of stock on hand
before unlocking. Daylight publishes no firmware images, so "keep a copy" is
easier said than done — this is what actually works, and the two obvious
routes that don't.

## What works: Daylight's own OTA server hands you the full zip

The stock updater (`com.ota.manager`) authenticates against
`https://updates.daylight.ink` and gets back a pre-signed S3 URL for the
**complete OTA zip**. Any registered serial can ask for it:

1. `GET /device/time` — server timestamp
2. RSA-encrypt `<serial>:<timestamp>` with the updater's public key
3. `POST /device/register` — exchange that for an access token
4. `POST /device/check-update` — pre-signed S3 URL (valid 12 h)
5. download

A community implementation lives in
[`adiktofsugar/daylight`](https://github.com/adiktofsugar/daylight) as
`download_ota/main.py`, alongside `bin/payload-dumper-go` for unpacking the
result and a HuggingFace dataset of already-pulled images. That zip is the
practical "copy of stock" this repo's docs ask for — pull it **before** you
unlock, while the device still boots stock.

One gotcha when running it: the server only returns an image if you tell it
the build you're on, and `--os-version` is a MediaTek build string
(`0.9.9.58.prod.2-1407`), not a date. It is read from `gsm.sn1`, falling back
to `ro.mediatek.version.release` — **both are system properties, so they read
empty from inside a DSU or after the GSI flash**. Capture them on stock, or
pass `--os-version` by hand later.

Unpacked, the zip gives you the `boot`, `vbmeta`, `system`, `vendor` and
`product` images — i.e. everything `fastboot` needs to put the unit back.

## What doesn't: `adb root` on this GSI

Making your own backup from a running GSI looks obvious and isn't. Lineage's
**Rooted debugging** toggle restarts `adbd` as uid 0, but it stays in the
`u:r:adbd:s0` domain with no `su` transition and aborts immediately:

```
adbd_auth: failed to listen on adbd authentication socket: Permission denied
```

`adbd` then crash-loops, over USB and TCP alike, so you lose adb entirely.
Recovery is toggling **Rooted debugging back off** — `adbd` restarts unrooted
and comes back. No `su` ships in the image either: `/system/xbin/phh-su` is
referenced by the Treble app but absent on disk.

(This is about the released `user` image. The `--userdebug` build in
`docs/root-walkthrough.md` is a different thing and does grant `adb root`.)

## What doesn't: mtkclient on this SoC

The usual MediaTek escape hatch is not realistic on the Helio G99 here: the
BROM is patched and SLA is device-dependent, so the generic exploit path is
closed. The one community success reported came from asking Daylight support
directly for a vendor DA file — which is a support ticket, not a procedure.

## Restoring

With the OTA zip unpacked, from fastbootd:

```bash
fastboot flash system  system.img
fastboot flash vbmeta  vbmeta.img      # re-enables verification if you want it
fastboot flash boot    boot.img        # NOTE: this also removes Magisk, if rooted
fastboot reboot
```

Flash `boot` only if you actually need it — on a rooted install that is the
step that drops root, and a system-only restore leaves Magisk in place.
