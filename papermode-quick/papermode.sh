#!/usr/bin/env bash
#
# papermode.sh — toggle DC-1 "paper" display settings over adb. No root needed.
#
# Drives only standard AOSP settings, so it works on stock *and* any GSI:
#   - grayscale via the accessibility color matrix (Monochromacy)
#   - refresh-rate caps via min/peak_refresh_rate (panel supports down to 6fps)
#
# Your original values are saved to .papermode_backup and restored by `off`.
#
# Usage:
#   ./papermode.sh status
#   ./papermode.sh on [read|eink|video]   (default: read)
#   ./papermode.sh off
#   ./papermode.sh grayscale on|off
#   ./papermode.sh refresh <fps>          (e.g. 6, 10, 30, 60)
#
set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP="$DIR/.papermode_backup"

# --- settings helpers --------------------------------------------------------
sget() { $ADB shell settings get "$1" "$2" 2>/dev/null | tr -d '\r'; }
sput() { $ADB shell settings put "$1" "$2" "$3"; }
sdel() { $ADB shell settings delete "$1" "$2" >/dev/null 2>&1 || true; }

require_device() {
  if ! $ADB get-state >/dev/null 2>&1; then
    echo "No device over adb. Connect the DC-1 and enable USB debugging." >&2
    exit 1
  fi
}

backup_once() {
  # Only capture originals the first time we turn paper mode on.
  [ -f "$BACKUP" ] && return 0
  {
    echo "DALT_EN=$(sget secure accessibility_display_daltonizer_enabled)"
    echo "DALT_MODE=$(sget secure accessibility_display_daltonizer)"
    echo "MINRR=$(sget system min_refresh_rate)"
    echo "PEAKRR=$(sget system peak_refresh_rate)"
  } > "$BACKUP"
  echo "Saved originals to $BACKUP"
}

# --- features ----------------------------------------------------------------
grayscale() {
  case "$1" in
    on)  sput secure accessibility_display_daltonizer_enabled 1
         sput secure accessibility_display_daltonizer 0          # 0 = Monochromacy
         echo "grayscale: ON" ;;
    off) sput secure accessibility_display_daltonizer_enabled 0
         echo "grayscale: OFF" ;;
    *) echo "grayscale on|off" >&2; exit 2 ;;
  esac
}

refresh() {
  local fps="$1"
  sput system min_refresh_rate "$fps"
  sput system peak_refresh_rate "$fps"
  echo "refresh pinned to ${fps}fps"
}

status() {
  echo "== Paper Mode status =="
  echo "grayscale enabled : $(sget secure accessibility_display_daltonizer_enabled)  (mode $(sget secure accessibility_display_daltonizer); 0=mono)"
  echo "min_refresh_rate  : $(sget system min_refresh_rate)"
  echo "peak_refresh_rate : $(sget system peak_refresh_rate)"
  echo "screen_brightness : $(sget system screen_brightness)"
  echo "amber_rate (stock): $(sget system screen_brightness_amber_rate)"
  echo "backup present    : $([ -f "$BACKUP" ] && echo yes || echo no)"
}

on() {
  backup_once
  local preset="${1:-read}"
  case "$preset" in
    read)  grayscale on; refresh 30 ;;   # comfortable e-ink-ish reading
    eink)  grayscale on; refresh 10 ;;   # ultra-calm, minimal flicker
    video) grayscale off; refresh 60 ;;  # back to smooth/color for media
    *) echo "presets: read | eink | video" >&2; exit 2 ;;
  esac
  echo "paper mode ON ($preset)"
}

off() {
  if [ ! -f "$BACKUP" ]; then
    echo "No backup found; resetting to sane defaults."
    sput secure accessibility_display_daltonizer_enabled 0
    sdel system min_refresh_rate
    sdel system peak_refresh_rate
    return 0
  fi
  # shellcheck disable=SC1090
  . "$BACKUP"
  restore_secure accessibility_display_daltonizer_enabled "$DALT_EN"
  restore_secure accessibility_display_daltonizer "$DALT_MODE"
  restore_system min_refresh_rate "$MINRR"
  restore_system peak_refresh_rate "$PEAKRR"
  rm -f "$BACKUP"
  echo "paper mode OFF (originals restored)"
}

restore_secure() { [ "$2" = "null" ] && sdel secure "$1" || sput secure "$1" "$2"; }
restore_system() { [ "$2" = "null" ] && sdel system "$1" || sput system "$1" "$2"; }

# --- dispatch ----------------------------------------------------------------
require_device
cmd="${1:-status}"; shift || true
case "$cmd" in
  status)    status ;;
  on)        on "${1:-read}" ;;
  off)       off ;;
  grayscale) grayscale "${1:-}" ;;
  refresh)   refresh "${1:?usage: refresh <fps>}" ;;
  *) echo "usage: $0 {status|on [read|eink|video]|off|grayscale on|off|refresh <fps>}" >&2; exit 2 ;;
esac
