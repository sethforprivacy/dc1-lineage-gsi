# The two hardware buttons

The DC-1 has two physical buttons besides power and volume — the **side orange
button** and the **top orange button**. On any GSI both are dead, and it isn't
obvious why: nothing in the ROM is broken, the keys simply have nowhere to go.

## Why they go inert on a GSI

`mtk-kpd` (`/dev/input/event1`) exposes them as plain function keys, and
`/vendor/usr/keylayout/mtk-kpd.kl` maps scancodes 87 and 88:

| button | scancode | keycode |
|---|---|---|
| side orange button | 87 | `KEY_F11` (141) |
| top orange button | 88 | `KEY_F12` (142) |

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

- **F11 (side)** only ever showed a toast: *"Walkie-Talkie assistant
  is coming soon!"* — a stub for a feature Daylight never shipped. There is no
  real behaviour to be faithful to, so the button is free.
- **F12 (top)** called `handleTopButton()`, launching
  `com.fluidtouch.noteshelf2`, falling back to `noteshelf3`, else toasting
  "No note-taking app found".

## What this fork binds them to

`DC1KeyHandler` — a small `DeviceKeyHandler` loaded by LineageOS'
`PhoneWindowManager` via `config_deviceKeyHandlerLibs` (set in
`overlay-lineage/`). No root, no new daemon, nothing to keep running.

Each button has a short-press and a long-press action. Settings → System →
DC-1 buttons sets them:

| press | default |
|---|---|
| side orange button (F11), short | digital assistant |
| side orange button (F11), long | frontlight off and on |
| top orange button (F12), short | default notes app |
| top orange button (F12), long | nothing |

The choices for each press:

- **Digital assistant.** The handler calls `SearchManager.launchAssist()`, the
  same path `PhoneWindowManager` uses for a long-press on home, so the app
  that holds Android's assistant role opens. No app is hard-coded: choose one
  under Settings → Apps → Default apps → Digital assistant app.
- **Frontlight off and on.** Off saves the current `screen_brightness` in the
  `Settings.System` key `dc1_frontlight_saved_brightness` and sets the
  brightness to 1, the level at which the lights HAL disables both LED
  drivers. On restores the saved value, so the light comes back as it was,
  also after a reboot. On or off is read from the brightness itself, there is
  no separate flag:

  | state when pressed | result |
  |---|---|
  | brightness above 1 | save it, set brightness to 1 |
  | brightness 0 or 1, value saved | restore the saved value |
  | brightness 0 or 1, nothing saved | system default brightness |

  Moving the brightness slider while the light is off drops the saved value.
  The amber warmth is not touched, see [`amber.md`](amber.md).
- **Default notes app.** The handler starts `ACTION_CREATE_NOTE` in the app
  that holds Android's Notes role. `rro/DC1Overlay` sets
  `config_enableDefaultNotes`, which AOSP leaves off, so Settings → Apps →
  Default apps shows a "Notes app" entry. Only apps that handle
  `ACTION_CREATE_NOTE` are offered there. With no notes app set the press
  does nothing.
- **Open an app.** Any installed app, for apps that cannot hold the Notes
  role (Noteshelf, for example).
- **Nothing.**

When a button has a long-press action, its short-press action runs when the
button is released. With "Nothing" on the long press it runs at once.

The choices are kept in `Settings.Secure` (`dc1_button_side_short`,
`dc1_button_side_long`, `dc1_button_top_short`, `dc1_button_top_long`) as
`assistant`, `frontlight`, `notes`, `none` or `app:<package>`.

Both are handled only while the display is interactive, and both key events
are consumed so apps never see a stray F11/F12.

The overlay keeps the lineage-sdk default handler (LineageParts) in front of
`DC1KeyHandler`, because a build-time overlay replaces the whole array.

## Testing a button

Press the button and read the handler's log:

```bash
adb logcat -s DC1KeyHandler
```

Key injection from adb does not reach the handler on a non-root build:

- `input keyevent` injects above the input device, so a device key handler
  never sees it.
- `sendevent` on `/dev/input/event1` (scancode 87 = side, 88 = top) is the
  right level, but SELinux denies it to the adb shell. It needs root.

## If they stay dead

The handler logs nothing until a key arrives, so check the load first:

```bash
adb logcat -b all | grep -i "device key handler"  # PWM logs instantiation failures
adb shell ls -l /system_ext/app/DC1KeyHandler/DC1KeyHandler.apk
adb shell cmd overlay lookup --user 0 lineageos.platform \
    lineageos.platform:array/config_deviceKeyHandlerLibs
```

The build turns `overlay-lineage/` into an auto-generated runtime overlay on
`lineageos.platform`; the last command shows whether it lists the handler.

`PhoneWindowManager` catches and logs any exception from the constructor, so a
handler that fails to load fails silently from the user's point of view.
