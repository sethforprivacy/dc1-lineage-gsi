#!/system/bin/sh
#
# amberctl — set/get the DC-1 amber frontlight (root). Installed to
# /system/bin/amberctl by the Magisk module; also runnable directly.
#
# Single source of truth is the stock setting `screen_brightness_amber_rate`
# (0..1023), so any UI that writes it (incl. leftover Daylight settings, the
# PaperMode app, or Tasker) stays in sync. amberctl writes the setting AND the
# LED node immediately for responsiveness; the boot daemon keeps them mirrored.
#
# Usage:
#   amberctl set <0..1023>
#   amberctl get
#   amberctl max
#   amberctl off
#   amberctl sync          # push current setting value to the LED now

CONF=/data/adb/amberd/config
[ -f "$CONF" ] || { echo "No config at $CONF. Run discover-amber-node.sh first." >&2; exit 1; }
# shellcheck disable=SC1090
. "$CONF"

setting_get() { settings get system screen_brightness_amber_rate 2>/dev/null | tr -d '\r'; }
setting_put() { settings put system screen_brightness_amber_rate "$1"; }

write_led() {
  # scale value (0..SETTING_MAX) -> node (0..AMBER_NODE_MAX)
  v="$1"
  [ "$v" -lt 0 ] 2>/dev/null && v=0
  [ "$v" -gt "$SETTING_MAX" ] 2>/dev/null && v="$SETTING_MAX"
  scaled=$(( v * AMBER_NODE_MAX / SETTING_MAX ))
  echo "$scaled" > "$AMBER_NODE" 2>/dev/null || {
    echo "write to $AMBER_NODE failed (SELinux? not root?)" >&2; exit 1; }
}

case "${1:-get}" in
  set)  v="${2:?usage: amberctl set <0..1023>}"; setting_put "$v"; write_led "$v"; echo "amber=$v" ;;
  get)  echo "amber=$(setting_get)  node=$(cat "$AMBER_NODE" 2>/dev/null)" ;;
  max)  setting_put "$SETTING_MAX"; write_led "$SETTING_MAX"; echo "amber=max" ;;
  off)  setting_put 0; write_led 0; echo "amber=off" ;;
  sync) write_led "$(setting_get)"; echo "synced" ;;
  *) echo "usage: amberctl {set <0..1023>|get|max|off|sync}" >&2; exit 2 ;;
esac
