# Paper Mode (Android app)

A no-root reading-mode controller for the DC-1. Bundles the three app-reachable
"paper" mechanisms plus a hook for the hardware amber LED on a rooted GSI.

## Features
- **Grayscale** toggle (accessibility Monochromacy color matrix)
- **Refresh pacing** presets (60 / 30 / 10 / 6 / Auto) — your panel supports down to 6 fps
- **Software warm filter** — adjustable blue-light-reduction overlay (no root)
- **Ghost-clear flash** — white→black sweep to reset LCD smearing
- **Hardware amber slider** — writes `screen_brightness_amber_rate`; lights the real LED when the root daemon is installed
- **Quick Settings tile** — one tap = grayscale + 30 fps + warm filter

## Build
Open in Android Studio (Giraffe+), or CLI:
```bash
cd PaperMode-app
./gradlew assembleDebug          # add a gradle wrapper first if missing (below)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
No gradle wrapper committed. Generate one with a local Gradle 8.7+:
```bash
gradle wrapper --gradle-version 8.7
```

## Permissions (grant once)
```bash
# Grayscale lives in the Secure namespace — only adb can grant this:
adb shell pm grant com.dc1.papermode android.permission.WRITE_SECURE_SETTINGS
```
Then in-app, tap **Grant 'Modify system settings'** (for refresh + amber) and
**Grant 'Display over other apps'** (for the warm filter / ghost-clear). The
home screen shows live status of all three.

## What needs root vs not
| Control | Root? |
|---|---|
| Grayscale, refresh, warm filter, ghost-clear | No |
| Hardware amber LED slider actually lighting up | Yes — install the `amber-root` Magisk module + run `discover-amber-node.sh` |

Without the daemon, the amber slider just stores a value (harmless); with it,
the slider drives the real frontlight.

## Honest scope
True per-pixel e-ink dithering of arbitrary screen content isn't possible from
an app (an overlay can't read the pixels beneath it). This app gets you the
practical reading experience — mono + slow refresh + warmth + ghost-clear —
which covers most of what makes the DC-1 pleasant to read on.
