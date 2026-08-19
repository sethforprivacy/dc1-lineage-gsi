# DC-1 hardware / Treble facts

Verified against a live unit (adb) on 2026-08-19, every property below is from
`getprop`/sysfs on stock Android 13 (build `TP1A.220624.014`, security patch
2026-01-05).

## SoC / boot

| Property | Value |
|---|---|
| `ro.product.device` | `jagar` |
| `ro.product.model` | `DC_1` |
| `ro.hardware` / `ro.board.platform` | `mt8781` / `mt6789` (MediaTek Helio G99) |
| Stock system | Android 13 (`13/TP1A.220624.014`) |
| Vendor VNDK | `ro.vndk.version=31` → any **Android 13+ GSI** is compatible |
| `ro.boot.slots` | `true` (A/B) |
| Dynamic partitions | `true` |
| Partition layout | MediaTek MSSI (`sys_mssi_t_64_cn_armv82_wifi_jagar`) — system image is decoupled from vendor by design |
| Bootloader | locked (`ro.boot.vbmeta.device_state=locked`, verified boot `green`) — must be unlocked once to flash |
| Security patch on stock | 2026-01-05 (a current build; GSI still improves on it via monthly ASB) |

The MSSI designation is notable: the stock firmware is itself built like a
swappable system image, which is why GSI flashing works cleanly and why
MediaTek-flavored GSIs (TrebleDroid) behave well here.

## Display / "Live Paper" panel

- 1200x1600, default 60 fps with alternative refresh rates down to **6 fps**
  (6 / 10 / 15 / 24 / 30 / 45 / 72 / 90 / 120) — the low rates are what make
  the "e-ink pacing" reading mode work (`papermode-quick/`).
- Reflective LCD ("Live Paper"), grayscale-native with software color options.
- Frontlight: white LED string + a separate **amber** LED channel for the warm
  / blue-light-free reading light.

## Amber frontlight

- Driven on stock from the `Settings.System` key
  **`screen_brightness_amber_rate`**, range **0..1023**.
- The write path lives in the vendor HAL/kernel (not in any user-space app
  string — see `docs/amber.md`); on stock, Daylight's privileged
  `com.daylightcomputer.systemrunner` bridges the setting to the kernel LED.
- The kernel node lives under `/sys/class/leds/` (SELinux type `sysfs_leds`),
  gated by SELinux on stock — readable/writable only with our ROM's sepolicy
  (or with root on stock).
- Scale note: Daylight's app slider shows 0..255; `255` (full) ≈
  `1023` (full) in the setting. This fork's default is **amber on at full**
  (1023), matching "Amber: 255".
- If a per-build override is needed, set the system property
  `ro.dc1.amber.node=/sys/class/leds/<node>/brightness` in the ROM; the app
  also auto-discovers the node on first boot.

## Software stack (stock, for reference)

| Package | Location | Role |
|---|---|---|
| `com.daylightcomputer.systemrunner` | `/system/system_ext/priv-app/SystemRunner/` | privileged runner that drives the amber frontlight on stock |
| `com.jangleinc.solos_launcher` | `/system/priv-app/solos_launcher/` | Niagara-derived "Paper" launcher |
| `com.daylightcomputer.deviceautomator` | `/system/system_ext/priv-app/DeviceAutomator/` | automation glue |
| `com.daylightcomputer.outofboxexperience` / `onboarder` | system_ext | OOBE/setup |

On a GSI none of these survive, which is why the amber bridge is re-created as
`AmberControl` inside the ROM (see `docs/amber.md`).

## Constraints for the fork

- GSI must be **Android 13+** (VNDK 31 rule) → LineageOS 23.2 (API 36) is fine.
- **A/B, dynamic partitions** → system-as-root image flashed via fastboot to
  both slots or via DSU for a test boot before committing.
- Kernel/vendor partitions are **untouched** on a GSI flash — the amber LED
  driver survives; only the software bridge is replaced.
