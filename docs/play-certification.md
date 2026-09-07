# Play certification on the DC-1 (Play Store access)

## What's going on (2026-09)

A non-Google build (LineageOS GSI) is **not Play Protect certified** — Google has
no CTS record for it, and certification is only ever granted for Google's own
builds (including their official GSI). That used to be cosmetic: Play Store
installed and updated apps anyway, with a "not certified" notice. Since the
2026 enforcement, Google's docs say an uncertified device **may not get app
updates**, and in practice the Play Store on the DC-1 block-stops new installs
and updates with "This device isn't Play Protect certified".

Two independent layers — don't confuse them:

- **Play Protect device certification** — decides whether the Play Store lets
  you install/update apps at all. *This* is what we can fix (see below).
- **Play Integrity** — per-app attestation (Basic / Device / Strong) used by
  banking, Wallet, etc. Registration does **not** fix Play Integrity; the
  hardware-backed Strong verdict can never pass on an unlocked, non-Google
  GSI, by design. Apps that only need Basic/Device may still work.

## Fix: register the GSF ID (official Google path)

Google's own custom-ROM warning page says: *"To use Google apps with a custom
ROM, register this device"* → `g.co/AndroidDeviceRegistration` →
`https://www.google.com/android/uncertified/`.

### 1. Get the GSF ID (16 hex digits, "Google Services Framework ID")

Try in order:

1. **Device-ID app** — e.g. "Device ID" (`com.redphx.deviceid`) or "My Device
   IDs: GSF GAID viewer" (`com.github.kolacbb.ids`); look for the **GSF ID /
   Google Services Framework** field. Caveat: newer GMS restricts apps from
   reading it (GrapheneOS tracker #4574) — if the field is blank, use next.
2. **ADB** (user build, no root needed):
   ```
   adb shell content query --uri content://com.google.android.gsf.gservices --where "name='android_id'" --projection value
   ```
   If rejected on Android 16, use route 3.
3. **One-off userdebug build** (we control the build, so this is deterministic):
   - `./tools/build-release.sh --userdebug` then `fastboot flash system system.img`
     **without wiping /data** (the current Play Store setup stays →
     same GSF ID).
   - `adb root` then read the framework DB:
     ```
     adb pull /data/data/com.google.android.gsf/databases/gservices.db
     sqlite3 gservices.db "select * from main where name='android_id';"
     ```
     (`sqlite3` binary usually isn't on the device; pull and read on the host.)
   - Note the ID, flash the normal `user` release back. The GSF ID lives in
     `/data` (`/data/data/com.google.android.gsf`), so it survives the swap;
     the registration stays valid.

The ID may show a `0x` prefix and/or uppercase — submit the bare 16 hex
digits (strip `0x`, keep lowercase).

### 2. Register

1. Open `https://www.google.com/android/uncertified/` in any browser.
2. Sign in with the **same Google account** that's on the DC-1.
3. Paste the GSF ID, solve the reCAPTCHA, press **Register**.
4. Wait 10–30 minutes (sometimes longer).

### 3. Apply

On the device: force-stop and clear data for **Google Play Store** and
**Google Play services** (Settings → Apps → … → Clear data), or:

```
adb shell pm clear com.android.vending
adb shell pm clear com.google.android.gms
```

then remove/re-add the Google account if needed and reboot.

### 4. Verify

Play Store → profile icon → Settings → About → **Play Protect certification**.
Reports differ on whether the label flips to "Certified"; the practical test is
functional — install and update an app. Per current reports that works once the
registration lands.

## If Google rejects the registration

Registrations aren't guaranteed (recent threads report "Unable to register");
options, in order of cost to this project's constraints:

- **Root + Play Integrity module** (Magisk/Zygisk + PIFork/Tricky Store):
  restores certified + Basic/Device integrity, but Strong still fails on
  unlocked hardware, it's cat-and-mouse maintenance, and it violates the
  unrooted pillar of this fork. Only if that tradeoff is accepted.
- **Aurora Store**: unofficial client that needs no certification — but as of
  2026-09-01 Google is actively blocking Aurora's anonymous accounts
  ("Server busy", AuroraOSS #1566, HN thread). Don't design the base around
  it today; treat as an emergency sideload source and re-evaluate later.
- **Base switch** (only if Play Store access is a hard requirement over ASB
  cadence): Google official GSI is the only certified GMS image, but it was
  rejected in `rom-choice.md` for cadence/maintainability; MisterZtr's GAPPS
  (bgN) variant is equally uncertified, so it buys nothing here.

## Sources

- Google support: "Check & fix Play Protect certification status" (7165974 / 10248227)
- `google.com/android/uncertified/warningauto` — custom-ROM registration path
- K3V1991/Fix-This-Device-isnt-Play-Protect-certified (registration workflow)
- TheLeaker "Device Isn't Play Protect Certified" (Aug 2026, current flow)
- AuroraOSS/AuroraStore #1566 + HN thread (Sept 2026, Aurora being blocked)
- GrapheneOS os-issue-tracker #4574 (GSF ID app access restrictions)
