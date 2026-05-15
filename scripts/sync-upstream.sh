#!/usr/bin/env bash
# sync-upstream.sh — Pull the latest upstream Velocity changes and merge them.
# Run this periodically to keep Conduit current with PaperMC updates.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
UPSTREAM_DIR="$ROOT_DIR/.upstream-velocity"
UPSTREAM_BRANCH="dev/3.0.0"

echo "==> Fetching upstream changes..."
git -C "$UPSTREAM_DIR" fetch origin "$UPSTREAM_BRANCH"

OLD_HEAD=$(git -C "$UPSTREAM_DIR" rev-parse HEAD)
git -C "$UPSTREAM_DIR" checkout "origin/$UPSTREAM_BRANCH"
NEW_HEAD=$(git -C "$UPSTREAM_DIR" rev-parse HEAD)

if [[ "$OLD_HEAD" == "$NEW_HEAD" ]]; then
  echo "==> Upstream is already up to date ($OLD_HEAD)."
  exit 0
fi

echo "==> Upstream advanced: $OLD_HEAD → $NEW_HEAD"
echo "==> Changelog since last sync:"
git -C "$UPSTREAM_DIR" log --oneline "$OLD_HEAD..$NEW_HEAD"

echo ""
echo "==> Re-running setup to apply upstream changes..."
"$SCRIPT_DIR/setup.sh"

echo ""
echo "==> Upstream sync complete."
echo "    Review any conflicts in overlays/ before committing."
