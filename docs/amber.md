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
│  • AmberService                – SettingsObserver on screen_brightness_amber_rate,  │
│                                   writes scaled value to the amber LED node         │
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

## Live channel mapping (diagnostics)

**Open question as of the last on-device session.** The channel↔string mapping
is not settled on a GSI:

| observation | verified live |
|---|---|
| `/sys/class/leds/lcd-backlight-amber/brightness` (chip i2c `2-003c`) written to 255 | accepts the write, emits **zero light** — a physical no-op, not a DAC/SELinux/scaling failure |
| Android's own brightness pipeline (HWC `setDisplayBrightness`; the lights HAL exposes no lights at all) | **does** emit light, and the light **is amber** — which string it drives is unknown |
| `/sys/class/leds/lcd-backlight/brightness` (chip i2c `5-003c`) | never driven directly; DAC blocked it (root:root 0644, and the old rc only chmod'd the amber node) |

The leading hypothesis for the dead amber node is a **driver-side gate**: the
chip device dir exposes Daylight-custom attributes, among them
`hwcomposer_disabled_backlight_off_bypass`, which reads like an escape hatch
from a "hwcomposer disabled ⇒ backlight off" interlock that the stock display
stack satisfied and a GSI does not. `dc1-amber.rc` now writes `1` to it on
**both** chips (init can: `/sys/bus/i2c/devices/*-003c/*` is plain `sysfs`,
which the shell may not write and init may — see `sepolicy/dc1amber.te`), and
chmods **both** leds-class brightness nodes so either can be driven from
userspace.

To make the remaining experiments possible **without another flash**, both
halves of the mix are re-read from `Settings.System` on every mirror pass
(nothing is cached across a change):

| key | values | meaning |
|---|---|---|
| `dc1_amber_node` | absolute path | the amber brightness node; unset = normal resolution (prop → persisted → discovery) |
| `dc1_white_mode` | `brightness` | **default.** White half = the `screen_brightness` setting, i.e. the framework/HWC pipeline |
| | `node:<path>` | white half = that sysfs node, scaled to **its own** `max_brightness`; `screen_brightness` is left alone |
| | `off` | the white half is not driven at all |

The rate model is unchanged: `amber = (B/255) * w * amber_node_max` and
`white = max(floor, B * (1 - w))`, with B the lamp total (read from
`screen_brightness`) and w the warmth rate. `node:` mode only rescales the
white share to the node's max at the last step. Unparseable values fall back
to the defaults with a warning; the keys are also observed, so a `settings
put` applies immediately.

Every mirror logs one line with the resolved config and both channel values:

```
I AmberControl: mix: amber_node=/sys/class/leds/lcd-backlight-amber/brightness \
  white_mode=node:/sys/class/leds/lcd-backlight/brightness \
  B=200 w=0.500(512/1023) -> amber=100/255 white=803/2047
```

### Driving the experiments over adb

```bash
adb logcat -c && adb logcat -s AmberControl &     # watch the mix lines

# 0. chip state. The attrs ship root-only, so init chmods them 0644 at
#    boot_completed — the shell can then read (never write) the driver's
#    own view of both chips: 2-003c is the lcd-backlight-amber chip,
#    5-003c the lcd-backlight one.
for c in 2 5; do
  echo "== chip $c-003c"
  adb shell cat /sys/bus/i2c/devices/$c-003c/hwcomposer_disabled_backlight_off_bypass
  adb shell cat /sys/bus/i2c/devices/$c-003c/i2c_brightness   # driver's own value
  adb shell cat /sys/bus/i2c/devices/$c-003c/registers        # full register dump
done

# 1. does the amber node light up now that the gate is bypassed?
adb shell settings put system dc1_white_mode off              # white out of the way
adb shell settings put system screen_brightness_amber_rate 1023
adb shell 'echo 255 > /sys/class/leds/lcd-backlight-amber/brightness'   # also by hand
adb shell cat /sys/class/leds/lcd-backlight-amber/brightness
adb shell cat /sys/bus/i2c/devices/2-003c/i2c_brightness       # did the chip value move?

# 2. what is the OTHER chip's string? (5-003c, never driven before)
adb shell settings put system dc1_amber_node /sys/class/leds/lcd-backlight/brightness
adb shell settings put system screen_brightness_amber_rate 1023   # full "amber" on 5-003c
adb shell settings put system screen_brightness_amber_rate 0      # off again
adb shell cat /sys/bus/i2c/devices/5-003c/i2c_brightness

# 3. two-node crossfade: amber on one string, white on the other,
#    with screen_brightness (the pipeline that DOES emit) left alone
adb shell settings put system dc1_amber_node /sys/class/leds/lcd-backlight-amber/brightness
adb shell settings put system dc1_white_mode node:/sys/class/leds/lcd-backlight/brightness
adb shell settings put system screen_brightness_amber_rate 512

# 4. back to the shipped behaviour (white via the framework pipeline)
adb shell settings delete system dc1_amber_node
adb shell settings delete system dc1_white_mode
```

Note `dc1_white_mode=off` and `node:` both hand the user's captured brightness
base back to `screen_brightness` on the way out, so switching modes live never
leaves the slider stranded at a crossfaded value.

Chip state is the instrument that makes the gate observable without eyes on
the panel: if a brightness write moves `i2c_brightness`/`registers` on one chip
but not the other, the difference is the node mapping; if it moves neither, the
gate is still shut. `dc1-amber.rc` chmods `registers`, `i2c_brightness`,
`hwcomposer_disabled_backlight_off_bypass` and
`can_not_see_backlight_brightness_threshold` to 0644 on both chips at
`boot_completed`, so plain `cat` reads them live — no snapshotting, no props.

> **Dead trigger, kept for the record.** `dc1-amber.rc` also carries an
> `on property:debug.dc1.regs=*` block that tries to mirror the same attrs into
> `debug.dc1.*` props with `read <file> <prop>`. **`read` is not an Android init
> command** (the command list has `write`, `copy`, `copy_per_line`, `chmod`,
> `setprop`, `readahead` — no `read`), so init rejects those eight lines at
> parse time (`invalid command 'read'`) and the props are never set. Use the
> `cat` reads above; the block can be deleted once that is confirmed on-device.

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
adb shell settings put system screen_brightness_amber_rate 0   # amber off
adb shell settings put system screen_brightness_amber_rate 1023  # amber full
```

Watch the frontlight; the LED response is instant (the service writes on
every setting change).

## Legacy root tooling

`amber-root/` holds the pre-GSI bridge used before this fork existed: a
Magisk module + `discover-amber-node.sh` (root-only, interactive) for finding
the exact node on a rooted device, and `amberctl.sh` (root CLI). These stay
useful **on stock still rooted, or on a userdebug build**, and the matched
discovery results can be pinned into `ro.dc1.amber.node`.
