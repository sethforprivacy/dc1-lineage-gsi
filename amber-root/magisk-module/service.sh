#!/system/bin/sh
# Magisk late_start service: launch the amber bridge daemon once booted.
MODDIR=${0%/*}

# Wait for boot to complete so the settings provider is up.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done

# Restore last amber value into the setting if it was saved (survives reboots
# via the setting itself; this is just a belt-and-suspenders no-op otherwise).
sh "$MODDIR/amberd.sh" &
