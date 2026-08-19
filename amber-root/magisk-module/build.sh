#!/usr/bin/env bash
# Build the flashable Magisk module zip. Copies the canonical amberctl in and
# produces dc1_amber.zip ready to flash in Magisk / KernelSU / APatch.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

mkdir -p system/bin
cp ../amberctl.sh system/bin/amberctl
chmod 0755 system/bin/amberctl amberd.sh service.sh uninstall.sh 2>/dev/null || true

OUT="$HERE/dc1_amber.zip"
rm -f "$OUT"
# Module files must sit at the zip root.
zip -r -X "$OUT" \
  module.prop service.sh amberd.sh uninstall.sh sepolicy.rule system \
  -x '*.DS_Store'
echo "Built: $OUT"
echo "Flash it in Magisk (Modules > Install from storage), reboot, then run discover-amber-node.sh."
