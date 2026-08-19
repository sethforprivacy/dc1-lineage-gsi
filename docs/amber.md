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
┌──────────────────────────── system_app domain (rootless) ───────────────────────────┐
│ AmberControl (platform-priv-app, system_ext/priv-app)                               │
│  • QS tile "Amber"             – toggle on (default value) / off                    │
│  • Settings activity+silder    – 0..1023                                │
│  • AmberService                – SettingsObserver on screen_brightness_amber_rate,  │
│                                   writes scaled value to the amber LED node         │
│  • Node resolution, in order:                                                       │
│      1. ro.dc1.amber.node (property override, set by maintainers)                   │
│      2. persisted choice (SharedPreferences)                                        │
│      3. auto-discovery on first boot:                                               │
│         - scan /sys/class/leds/*/brightness (+ /sys/class/backlight/*/brightness)   │
│         - drop the white backlight node (the one whose value tracks screen          │
│           brightness; class `backlight/` and name `lcd-backlight` are excluded)     │
│         - of the rest: prefer names with amber|warm|frontlight; take the single     │
│           remaining candidate otherwise; persist the choice                          │
└──────────────────────────────────────────────────────────────────────────────────────┘
               │ sepolicy: allow system_app sysfs_leds { dir search/read, file rw }
               ▼
        /sys/class/leds/<node>/brightness   (kernel driver, untouched vendorkernel)
```

- **Unrooted**: the app runs in the `system_app` domain under enforcing
  SELinux with a narrow rule (`sepolicy/dc1amber.te`). No su, no Magisk.
- **Scale mapping**: setting 0..1023 → node 0..`max_brightness`
  (`value * nodeMax / 1023`), the same scaling the old Magisk bridge used.
- **Persistence**: the setting is the source of truth (survives reboots);
  the service mirrors it to the LED immediately on change and on boot.

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
