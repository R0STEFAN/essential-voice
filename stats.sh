#!/usr/bin/env bash
# How many people have downloaded it. Not how many installed it — see README.
set -euo pipefail
GH=~/.local/opt/gh/bin/gh
REPO=$(git remote get-url origin 2>/dev/null \
  | sed -E 's#.*github\.com[:/]##; s#\.git$##')

echo "Downloads per release"
echo "---------------------"
$GH api "repos/$REPO/releases" --paginate \
  --jq '.[] | .assets[]? | "\(.name)\t\(.download_count)"' \
  | awk -F'\t' '{printf "  %-28s %6d\n", $1, $2; t+=$2} END {printf "  %-28s %6d\n", "TOTAL", t}'

echo
echo "Repository traffic (GitHub keeps 14 days)"
echo "-----------------------------------------"
$GH api "repos/$REPO/traffic/views" \
  --jq '"  page views   \(.count) (\(.uniques) unique)"' 2>/dev/null || echo "  unavailable"
$GH api "repos/$REPO/traffic/clones" \
  --jq '"  clones       \(.count) (\(.uniques) unique)"' 2>/dev/null || echo "  unavailable"
