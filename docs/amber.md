# The amber frontlight

The DC-1's reading light has two channels driven by the kernel LED driver:

- **white** — the normal backlight the framework already manages;
- **amber** — a second LED string for the warm / blue-light-free mode that
  the stock ROM calls "amber".

The amber channel is the one OS-bound piece of the DC-1 experience. On stock,
Daylight's privileged `systemrunner` app bridges the `Settings.System` key
**`screen_brightness_amber_rate`** (0..1023) to the kernel LED node. On a GSI
that glue is gone — but the kernel/vendor partitions are untouched, so the
LED and its driver survive. This fork re-creates the bridge **inside the ROM
as a rootless system app** (`AmberControl`), so nothing needs root and the
amber control survives reboots.

## Scale / "Amber: 255"

The authoritative value is the setting, 0..1023. Daylight's app shows a
0..255 slider; **255 (full) ≈ 1023 (full)**. This fork's default is **amber
on at full (1023)** — matching "always use Amber: 255". If you'd rather a
kinder default (e.g. `255` on the *setting's* own 0..1023 scale), change
`ro.dc1.amber.default` in `common.mk` and rebuild; or just drag the slider in
the `AmberControl` app once — the value persists in the setting.

## Architecture (baked into the image)

```
┌─────────────────────────── platform_app domain (rootless) ──────────────────────────┐
│ AmberControl (platform-priv-app, system_ext/priv-app)                               │
│  • QS tile "Amber"             – toggle on (default value) / off                    │
│  • Cool↔warm slider + presets  – 0..1023, saved in-app                  │
│  • AmberService                – mirrors the rate: writes BOTH LED nodes (amber +  │
│                                   white, see "How the frontlight actually works")  │
│  • Channel plumbing (live)     – dc1_amber_node / dc1_white_mode, re-read per pass  │
│  • Node resolution, in order:                                                       │
│      1. dc1_amber_node (Settings.System live override, diagnostics)                 │
│      2. ro.dc1.amber.node (property override, set by maintainers)                   │
│      3. persisted choice (SharedPreferences)                                        │
│      4. auto-discovery on first boot:                                               │
│         - scan /sys/class/leds/*/brightness (+ /sys/class/backlight/*/brightness)   │
│         - drop the white backlight node (the one whose value tracks screen          │
│           brightness; class `backlight/` and name `lcd-backlight` are excluded)     │
│         - of the rest: prefer names with amber|warm|frontlight; take the single     │
│           remaining candidate otherwise; persist the choice                          │
└──────────────────────────────────────────────────────────────────────────────────────┘
               │ sepolicy: TE allow on sysfs_leds + sysfs_leds is mlstrustedobject
               ▼
        /sys/class/leds/<node>/brightness   (kernel driver, untouched vendorkernel)
```

- **Unrooted**: the app runs in an app domain under enforcing SELinux with a
  narrow rule (`sepolicy/dc1amber.te`). No su, no Magisk.
- **Scale mapping**: setting 0..1023 → node 0..`max_brightness`
  (`value * nodeMax / 1023`), the same scaling the old Magisk bridge used.
- **Persistence**: the setting is the source of truth (survives reboots);
  the service mirrors it to the LED immediately on change and on boot.

## SELinux: two gates, not one

Writing the LED node from an app has to clear **two independent checks**, and
missing the second one is what makes an otherwise-correct policy fail with
`EACCES` and no obvious denial:

1. **Type enforcement.** AmberControl is a platform-signed priv-app, so
   `seapp_contexts` puts it in the **`platform_app`** domain (not
   `system_app`, which is a common wrong guess). The LED nodes are labelled
   `sysfs_leds`. `sepolicy/dc1amber.te` therefore allows `platform_app` (and
   `system_app`, harmlessly, for other build flavors) to search the LED class
   dirs, read the symlinks, and read/write `brightness`.

2. **MLS.** App domains get `levelFrom=user` in `seapp_contexts`, so
   `platform_app` runs at `s0:c512,c768`, while `sysfs_leds` nodes are plain
   `s0`. `system/sepolicy/private/mls` only permits a file write when the
   target is `app_data_file_type`/`appdomain_tmpfs`, the two levels are equal,
   the source is `mlstrustedsubject`, or the target is `mlstrustedobject` —
   none of which held. The fix is one line:

   ```
   typeattribute sysfs_leds mlstrustedobject;
   ```

   the same treatment AOSP already gives `sysfs_bluetooth_writable` and
   `sysfs_nfc_power_writable`. (`/sys/class/backlight/*` is plain `sysfs`,
   which is already `mlstrustedobject`, so the fallback path only needs the
   TE grant.)

The rules land in `system_ext_sepolicy.cil` via
`SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` (set in `common.mk`); neverallow
assertions are off in this GSI (`device/phh/treble/base.mk` sets
`SELINUX_IGNORE_NEVERALLOWS := true`), so the attribute change builds.

## How the frontlight actually works (verified on hardware)

Mapped live on the device, 2026-08-24, with the kernel log as the instrument
(`adb logcat -b kernel | grep -E '\[Light\] Set|rt4539'` shows every node write
with its sysfs value, every chip enable/disable with its reason, and every i2c
brightness transition):

| fact | evidence |
|---|---|
| Two frontlight chips, both Richtek **RT4539** (GPL driver `leds-rt4539.c` in Daylight's kernel drop) | `/sys/bus/i2c/devices/{2,5}-003c/driver -> rt4539`; DTS `bl_amb`/`bl_wht` nodes, enable GPIOs 99/98 |
| Amber string = `lcd-backlight-amber` (i2c `2-003c`); white string = `lcd-backlight` (i2c `5-003c`) | kernel log during the mapping experiments; DTS labels |
| Both strings are physically alive | `rt4539_enable: off-to-on` + `i2c_brightness_set: 0->…` on **both** nodes when driven directly; owner saw white and amber |
| The framework brightness pipeline goes through the **vendor lights HAL**, which writes BOTH nodes on every change with a fixed split: `white=1, amber=B-1` | HAL log lines `set_light_backlight: white=1 (brightness N)` / `amber=N-1` on every brightness ramp step |
| Why white never lit via the framework: sysfs 1 → hw level 16 = exactly the driver's on-threshold (`hw-brightness-on-threshold = <16>`, "hw brightness 1-16 ⇒ screen on backlight off") | kernel log `rt4539_disable: screen on backlight off` after every HAL white=1 write |
| The old "amber writes are a physical no-op" observation is fully explained: the app's node writes DID reach the chip — the HAL then overwrote both nodes milliseconds later during the brightness ramp | live kernel log: app write `209->3212` then HAL ramp writes over it |
| The driver's HWC gate exists but only bites at display-off/doze; the `hwcomposer_disabled_backlight_off_bypass=1` init write makes it a no-op (`rt4539_brightness_force_off: bypassed`) | kernel log at screen-off after the v8 build |

The HAL's ratio input is not public (closed-source Daylight binary) and not
driven by any framework-visible setting (night display / LiveDisplay don't
touch it). So the mix is driven **directly**: AmberControl writes both LED
nodes itself, and `screen_brightness` is only the lamp total B.

### The fight, and how it's won

The framework re-applies brightness — and the HAL re-asserts its white=1
split — on slider ramps, screen wake, boot, dim cycles and early-wake ramps
(the last two fire no setting change and no broadcast at all). AmberControl
therefore defends the mix at three levels:

- immediately on every observed setting change (the observers),
- once more ~2.5 s later, after the brightness ramp has fully settled
  (debounced: each new change pushes the re-assert out),
- on `ACTION_SCREEN_ON`, once after boot settle, and — the safety net that
  catches every remaining path — a **node watchdog** every 2 s: the driven
  nodes are read back (the LED class echoes the last value written, whoever
  wrote it) and compared with the last mix the app wrote; drift is corrected
  on the spot. This is what makes the frontlight self-heal after e.g. an
  Off→Full preset jump followed by a framework brightness re-apply.

All node writes run on the service's single handler thread, so the two
channels of one mirror pass can never land interleaved with another pass.

Both halves are re-read from `Settings.System` on every mirror pass, so a
`settings put` changes the plumbing live, no reflash:

| key | values | meaning |
|---|---|---|
| `dc1_amber_node` | absolute path | override the amber node; unset = normal resolution (prop → persisted → discovery) |
| `dc1_white_mode` | `node` | **default.** White half = the DC-1's white LED node directly (`ro.dc1.white.node` prop → `/sys/class/leds/lcd-backlight/brightness`) |
| | `node:<path>` | white half = that sysfs node, scaled to **its own** `max_brightness`; `screen_brightness` is left alone |
| | `brightness` | white half = the framework pipeline (the HAL's split applies — white will not light; kept for diagnosis/stock-like behaviour) |
| | `off` | the white half is not driven at all |

The rate model: `amber = (B/255) * w * amber_node_max` and
`white = round(B * (1 - w))` rescaled to the white node's max. In node mode
the white floor is 0 — at full warmth the panel is lit purely by amber (the
stock candlelight look, owner-verified). In brightness mode the floor is
`ro.dc1.amber.white_floor` (10): a dead amber node must not mean a dark panel.
Unparseable values fall back to the defaults with a warning.

One driver quirk matters at the extremes: **sysfs 0 is not the RT4539's off**.
Writing 0 to an enabled chip only fades it to the can-not-see threshold
(hw 1060 — a visible ~26% glow) and never disables it; writing **1** fades to
i2c 0 and arms the off-timer that disables the chip for real. (This is why the
vendor HAL's "off" is white=1.) AmberControl maps a computed 0 to a sysfs 1 in
node mode, so both ends of the slider are truly one color.

Every mirror logs one line with the resolved config and both channel values:

```
I AmberControl: mix: amber_node=/sys/class/leds/lcd-backlight-amber/brightness \
  white_mode=node:/sys/class/leds/lcd-backlight/brightness \
  B=200 w=0.500(512/1023) -> amber=100/255 white=803/2047
```

### Observing the plumbing over adb

```bash
adb logcat -s AmberControl        # the mix lines above
adb logcat -b kernel | grep -E '\[Light\] Set|rt4539'   # every chip-level effect
adb logcat -b main | grep set_light_backlight           # what the HAL is writing

# chip state (attrs ship root-only; init chmods them 0644 at boot_completed —
# if SELinux still blocks the shell, the kernel log above is the fallback):
for c in 2 5; do
  adb shell cat /sys/bus/i2c/devices/$c-003c/i2c_brightness   # driver's own value
  adb shell cat /sys/bus/i2c/devices/$c-003c/registers        # full register dump
done

# try a different plumbing live:
adb shell settings put system dc1_white_mode off
adb shell settings put system screen_brightness_amber_rate 1023
adb shell settings delete system dc1_white_mode    # back to the default (node)
```

Note `dc1_white_mode=off` and `brightness` both hand the user's captured
brightness base back to `screen_brightness` on the way out, so switching modes
live never leaves the slider stranded at a crossfaded value.

## If auto-discovery picks the wrong node

This should be rare (the DC-1 exposes exactly one plausible non-backlight LED
node). To override without rebuilding:

```bash
# as the ROM maintainer, once the node is known from a root session:
adb shell setprop ro.dc1.amber.node /sys/class/leds/<node>/brightness
# persist via common.mk:  PRODUCT_SYSTEM_DEFAULT_PROPERTIES += ro.dc1.amber.node=...
```

The app logs its node choice: `adb logcat -s AmberControl`.

## Verifying on a fresh flash

```bash
adb shell settings get system screen_brightness_amber_rate   # 1023 after first boot
adb shell settings put system screen_brightness_amber_rate 0   # pure white
adb shell settings put system screen_brightness_amber_rate 1023  # full amber
```

Watch the frontlight: the hue sweeps white↔amber with the setting, at constant
overall brightness (the lamp total is `screen_brightness`). The chip-level
view (`logcat -b kernel`) shows the inverse-pair node writes on every change.

## Legacy root tooling

`amber-root/` holds the pre-GSI bridge used before this fork existed: a
Magisk module + `discover-amber-node.sh` (root-only, interactive) for finding
the exact node on a rooted device, and `amberctl.sh` (root CLI). These stay
useful **on stock still rooted, or on a userdebug build**, and the matched
discovery results can be pinned into `ro.dc1.amber.node`.
