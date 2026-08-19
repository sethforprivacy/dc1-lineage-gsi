#!/system/bin/sh
#
# amberd — boot daemon that mirrors `screen_brightness_amber_rate` (0..1023)
# onto the kernel amber LED node. Re-creates the bridge that Daylight's
# systemrunner provided on stock. Idempotent: only writes the node on change.

CONF=/data/adb/amberd/config

# Wait for config (discover-amber-node.sh writes it). Don't spin forever.
i=0
while [ ! -f "$CONF" ] && [ "$i" -lt 120 ]; do sleep 2; i=$((i+1)); done
[ -f "$CONF" ] || exit 0
# shellcheck disable=SC1090
. "$CONF"

last=""
while true; do
  v=$(settings get system screen_brightness_amber_rate 2>/dev/null | tr -d '\r')
  case "$v" in
    ''|*[!0-9]*) v=0 ;;
  esac
  if [ "$v" != "$last" ]; then
    [ "$v" -gt "$SETTING_MAX" ] && v="$SETTING_MAX"
    scaled=$(( v * AMBER_NODE_MAX / SETTING_MAX ))
    echo "$scaled" > "$AMBER_NODE" 2>/dev/null
    last="$v"
  fi
  sleep 0.4
done
