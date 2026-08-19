#!/usr/bin/env bash
#
# notify-issue.sh — open or update the "Upstream update detected" tracking
# Issue. Runs inside the GitHub Actions workflow (gh + GH_TOKEN available).
#
# Env:
#   GH_TOKEN       required (actions token)
#   VRC            validation exit code (2 = failed, else ok)
#   VALIDATE_LOG   file with the validation output (default /tmp/validate.log)
#   DETECT_JSON    file with detect output   (default /tmp/detect.json)
#
# Behavior:
#   * keeps ONE open issue titled "Upstream update detected"
#   * if it exists: refreshes the body and adds a comment
#   * if missing: creates it
#   * labels: upstream-update + delta-valid | patch-conflict
#
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN required}"
DETECT="${DETECT_JSON:-/tmp/detect.json}"
VLOG="${VALIDATE_LOG:-/tmp/validate.log}"
VRC="${VRC:-0}"

TITLE="Upstream update detected"

lg_tag="$(jq -r '.lineage_gsi.tag // "n/a"' "$DETECT")"
lg_date="$(jq -r '.lineage_gsi.published // "n/a"' "$DETECT")"
td_tag="$(jq -r '.trebledroid.tag // "n/a"' "$DETECT")"
td_date="$(jq -r '.trebledroid.published // "n/a"' "$DETECT")"
prev_td="$(jq -r '.prev.trebledroid // "n/a"' "$DETECT")"
prev_lg="$(jq -r '.prev.lineage_gsi // "n/a"' "$DETECT")"
checked="$(date -u '+%Y-%m-%d %H:%M UTC')"

if [ "${VRC:-0}" = "0" ]; then
  status=":white_check_mark: **delta valid** — the DC-1 fragment + patchset still fit current upstream."
  label_status="delta-valid"
else
  status=":warning: **delta INVALID** — upstream moved and the DC-1 delta needs attention. Validation log below."
  label_status="patch-conflict"
fi

vlog="$(cat "$VLOG" 2>/dev/null || echo "(no validation log)")"

BODY="## Upstream release check — $checked

| Signal | Previous | Detected | Released |
|---|---|---|---|
| LineageOS GSI | \`$prev_lg\` | **\`$lg_tag\`** | $lg_date |
| TrebleDroid patches | \`$prev_td\` | **\`$td_tag\`** | $td_date |

$status

### Next steps
- Build: \`tools/build-release.sh\` (or follow \`docs/build.md\`)
- Flash guide: \`docs/flash.md\`
- Upload the resulting \`system.img\` to **Releases** when happy.

<details><summary>Validation log</summary>

\`\`\`text
$vlog
\`\`\`

</details>
"

existing="$(gh issue list --repo "$GITHUB_REPOSITORY" --state open \
  --search "\"$TITLE\" in:title" --json number,title \
  --jq '[.[] | select(.title == "'"$TITLE"'")] | .[0].number' | tr -d '\n')"

gh label create upstream-update --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 || true
gh label create "$label_status" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 || true
gh label create delta-valid --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 || true
gh label create patch-conflict --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 || true

if [ -n "$existing" ] && [ "$existing" != "0" ]; then
  gh issue edit "$existing" --repo "$GITHUB_REPOSITORY" \
    --body-file - <<<"$BODY" >/dev/null
  # reset labels: keep upstream-update, set the delta label, drop the other
  gh issue edit "$existing" --repo "$GITHUB_REPOSITORY" \
    --remove-label patch-conflict --remove-label delta-valid \
    --add-label "upstream-update" --add-label "$label_status" >/dev/null 2>&1 || true
  echo "Updated issue #$existing"
else
  gh issue create --repo "$GITHUB_REPOSITORY" \
    --title "$TITLE" \
    --label "upstream-update" \
    --label "$label_status" \
    --body "$BODY"
fi
