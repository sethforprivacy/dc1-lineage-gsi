#!/usr/bin/env bash
#
# flash-stock.sh — restore a Daylight DC-1 to stock Android 13 with MediaTek's
# SP Flash Tool, in console mode. Run on an x86_64 Linux host that the tablet is
# plugged into; MediaTek ships no macOS build and a USB-passthrough VM is not a
# reliable BROM target.
#
# The operation is hard-coded to "download" (Download Only). NEVER switch it to
# format / format-download: the stock image ships no nvram/nvdata/proinfo/
# protect* payload (all is_download=false in the scatter), so a whole-chip
# erase takes the tablet's serial number and WiFi/BT MAC with it and leaves
# nothing to restore them from. See docs/restore-stock.md.
#
# Expected layout (see docs/restore-stock.md):
#   $DC1_STOCK/fw/<build>.jagar.user-image/       extracted stock image
#   $DC1_STOCK/spflash/SP_Flash_Tool_v6.*_Linux/  extracted flash tool
#
# Usage: sudo DC1_STOCK=~/dc1-stock tools/flash-stock.sh
#
set -euo pipefail

STOCK="${DC1_STOCK:-$HOME/dc1-stock}"

[ "$(id -u)" -eq 0 ] || { echo "run as root: MediaTek BROM is root-only USB" >&2; exit 1; }


FW=$(find "$STOCK/fw" -maxdepth 1 -type d -name '*.jagar.user-image' 2>/dev/null | head -1)
TOOL=$(find "$STOCK/spflash" -maxdepth 1 -type d -name 'SP_Flash_Tool_v6.*_Linux' 2>/dev/null | head -1)
: "${FW:?no *jagar.user-image directory under $STOCK/fw — extract the stock zip there}"
: "${TOOL:?no SP_Flash_Tool_v6.*_Linux directory under $STOCK/spflash}"

AUTH="$FW/download_agent/authsv5_jagar.auth"
FLASHXML="$FW/download_agent/flash_signedDA.xml"
BIN="$TOOL/SPFlashToolV6"
LOG="$STOCK/flash-stock-$(date +%Y%m%d-%H%M%S).log"

for f in "$AUTH" "$FLASHXML" "$FW/MT6789_Android_scatter.xml" "$FW/download_agent/DA_BR_jagar.bin" "$BIN"; do
    [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
done
chmod +x "$BIN"

echo "== image: $(basename "$FW") =="
echo "== tool:  $(basename "$TOOL") =="

# The kernel will hand the preloader VCOM port to ModemManager, which breaks the
# DA handshake; the tool ships the rule that makes it back off (vendor 0e8d).
systemctl stop ModemManager 2>/dev/null || true
if [ -f "$TOOL/99-ttyacms.rules" ]; then
    install -m644 "$TOOL/99-ttyacms.rules" /etc/udev/rules.d/99-ttyacms.rules
    udevadm control --reload-rules
fi

echo "== power the tablet fully OFF, then plug in USB =="
cd "$TOOL"
exec ./SPFlashToolV6 -f "$FLASHXML" -a "$AUTH" -c download -t with 2>&1 | tee "$LOG"
