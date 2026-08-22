#!/usr/bin/env bash
#
# detect-upstream.sh — detect new upstream releases for the DC-1 GSI fork.
#
# Watches three signals (in priority order):
#   1. LineageOS GSI release  (MisterZtr/LineageOS_gsi) — THE upstream we
#      build from: his releases are the published LOS 23.2 GSI baselines
#      (manifest + patch layers) that our fork layers the DC-1 delta onto.
#   2. MisterZtr treble_manifest lineage-23.2 branch tip — the project list
#      our tree composes from; a move here means re-sync + re-validate.
#   3. TrebleDroid patches release (TrebleDroid/treble_experimentations,
#      newest incl. prereleases) — informational only for the LOS flow
#      (that kit targets AOSP trees), kept for delta comparison.
#
# Writes tools/upstream-state.json and prints a JSON summary with a
# "changed" flag. Exit 0 always (workflow inspects the JSON).
#
# Usage: detect-upstream.sh                (uses committed state file)
#        detect-upstream.sh /path/state    (alternate state file)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STATE="${1:-$HERE/upstream-state.json}"

api() { # <path>
  local path="$1"
  if command -v gh >/dev/null 2>&1; then
    gh api "$path" 2>/dev/null || echo 'null'
  else
    curl -fsSL -H "Accept: application/vnd.github+json" \
      ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
      "https://api.github.com$path" 2>/dev/null || echo 'null'
  fi
}

# Newest release (incl. prerelease) that carries the patches asset.
lg="$(api '/repos/MisterZtr/LineageOS_gsi/releases/latest')"

td="$(api '/repos/TrebleDroid/treble_experimentations/releases?per_page=30' \
  | jq -c '[.[] | select((.assets // [] | map(.name)) | index("patches-for-developers.zip"))][0]')"

lg_tag="$(jq -r '.tag_name // ""' <<<"$lg")"
lg_date="$(jq -r '.published_at // ""' <<<"$lg")"
td_tag="$(jq -r '.tag_name // ""' <<<"$td")"
td_date="$(jq -r '.published_at // ""' <<<"$td")"

prev_td="$(jq -r '.trebledroid.tag // ""' "$STATE" 2>/dev/null || true)"
prev_lg="$(jq -r '.lineage_gsi.tag // ""' "$STATE" 2>/dev/null || true)"

changed="false"
if [ -n "$lg_tag" ] && [ "$lg_tag" != "$prev_lg" ]; then changed="true"; fi
if [ -n "$td_tag" ] && [ "$td_tag" != "$prev_td" ]; then changed="true"; fi

cat > "$STATE.update" <<JSON
{
  "lineage_gsi": {
    "repo": "MisterZtr/LineageOS_gsi",
    "tag": "$lg_tag",
    "published": "$lg_date"
  },
  "trebledroid": {
    "repo": "TrebleDroid/treble_experimentations",
    "tag": "$td_tag",
    "published": "$td_date",
    "asset": "patches-for-developers.zip"
  },
  "lineage_branch": "lineage-23.2",
  "treble_manifest_branch": "android-16.0",
  "checked_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON

jq -n \
  --argjson st "$(cat "$STATE.update")" \
  --arg changed "$changed" \
  --arg prevTd "$prev_td" --arg prevLg "$prev_lg" \
  '{changed: $changed, prev: {trebledroid: $prevTd, lineage_gsi: $prevLg},
    lineage_gsi: $st.lineage_gsi, trebledroid: $st.trebledroid}'

# Only commit the state file on a real change (CI commits it back).
if [ "$changed" = "true" ]; then
  mv "$STATE.update" "$STATE"
else
  rm -f "$STATE.update"
fi
