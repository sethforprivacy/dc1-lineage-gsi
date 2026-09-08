# The two hardware buttons

The DC-1 has two physical buttons besides power and volume — the **orange one
on the side** and the **top** one. On any GSI both are dead, and it isn't
obvious why: nothing in the ROM is broken, the keys simply have nowhere to go.

## Why they go inert on a GSI

`mtk-kpd` (`/dev/input/event1`) exposes them as plain function keys, and
`/vendor/usr/keylayout/mtk-kpd.kl` maps scancodes 87 and 88:

| button | scancode | keycode |
|---|---|---|
| orange, side | 87 | `KEY_F11` (141) |
| top | 88 | `KEY_F12` (142) |

**Neither F11 nor F12 has any default action in AOSP or LineageOS.** On stock
they worked only because Daylight's own privileged app
(`com.daylightcomputer.systemrunner`) listened for them — and that app is one
of the things a GSI replaces. So the buttons stop working the moment you
flash, with no error anywhere to explain it.

## What they did on stock

Recovered by pulling `SystemRunner.apk` out of the stock OTA's `system.img`
with `debugfs` (system-as-root ext4, no mount needed) and disassembling
`KeyHandler.handleKeyEvent`.

Worth care if you repeat this: the branch offsets **invert the obvious reading
order**, so the disassembly listing alone attributes the wrong action to each
key. Resolved against offsets — `if-eq 141` at 0019 jumps +0x0b to **0030**,
`if-eq 142` at 0023 jumps +3 to **0026**:

- **F11 (orange, side)** only ever showed a toast: *"Walkie-Talkie assistant
  is coming soon!"* — a stub for a feature Daylight never shipped. There is no
  real behaviour to be faithful to, so the button is free.
- **F12 (top)** called `handleTopButton()`, launching
  `com.fluidtouch.noteshelf2`, falling back to `noteshelf3`, else toasting
  "No note-taking app found".

## What this fork binds them to

`DC1KeyHandler` — a small `DeviceKeyHandler` loaded by LineageOS'
`PhoneWindowManager` via `config_deviceKeyHandlerLibs` (set in
`overlay-lineage/`). No root, no new daemon, nothing to keep running.

- **Orange (F11) → toggle the amber frontlight**, between off and the last
  warmth used (remembered in `dc1_amber_last_rate`). It moves AmberControl's
  own setting rather than touching the LED nodes, so the channel model, node
  discovery and the watchdog all still apply — the button is just a second
  way to move the same input as the QS tile. A physical control you can find
  in the dark beats a tile in the pull-down shade on a bedside reader.
- **Top (F12) → open notes**, generalising what Daylight hardcoded: an
  explicit override if set, else Android's **Notes role**
  (`ACTION_CREATE_NOTE` — whatever the user picked as their default notes
  app), else the two Noteshelf packages for stock parity.

Both are handled only while the display is interactive, and both key events
are consumed so apps never see a stray F11/F12.

The override for the top button is a plain setting:

```bash
adb shell settings put system dc1_notes_package com.example.notes
adb shell settings delete system dc1_notes_package      # back to the Notes role
```

## Testing a button without pressing it

`input keyevent` does **not** work here — it injects above the input device,
so a device key handler reading real hardware events never sees it, and
neither does `getevent`. Inject the raw scancode instead:

```bash
# key down + sync, then key up + sync  (87 = orange, 88 = top)
adb shell 'sendevent /dev/input/event1 1 87 1; sendevent /dev/input/event1 0 0 0'
adb shell 'sendevent /dev/input/event1 1 87 0; sendevent /dev/input/event1 0 0 0'

adb logcat -s DC1KeyHandler
```

## If they stay dead

The handler logs nothing until a key arrives, so check the load first:

```bash
adb shell dumpsys window | grep -i keyhandler     # or:
adb logcat -b all | grep -i "device key handler"  # PWM logs instantiation failures
adb shell ls -l /system_ext/app/DC1KeyHandler/DC1KeyHandler.apk
```

`PhoneWindowManager` catches and logs any exception from the constructor, so a
handler that fails to load fails silently from the user's point of view.
