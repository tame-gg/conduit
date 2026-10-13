#!/usr/bin/env bash
# sync-upstream.sh — Show how far upstream Velocity-CTD dev/4.0.0 has moved past the pinned commit.
# Run this periodically; moving the pin is a deliberate edit (see below).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
UPSTREAM_DIR="$ROOT_DIR/.upstream-velocity"
UPSTREAM_BRANCH="dev/4.0.0"
# setup.sh pins the exact commit (UPSTREAM_COMMIT). This script only shows what the branch has
# gained since the pin; bump UPSTREAM_COMMIT in setup.sh/setup.ps1/gradle.properties to move it.

echo "==> Fetching upstream changes..."
git -C "$UPSTREAM_DIR" remote set-url origin "https://github.com/GemstoneGG/Velocity-CTD.git"
git -C "$UPSTREAM_DIR" fetch origin "$UPSTREAM_BRANCH"

PINNED=$(sed -n 's/^UPSTREAM_COMMIT="\(.*\)"$/\1/p' "$SCRIPT_DIR/setup.sh")
LATEST=$(git -C "$UPSTREAM_DIR" rev-parse FETCH_HEAD)

if [[ "$PINNED" == "$LATEST" ]]; then
  echo "==> Pin is current with $UPSTREAM_BRANCH ($PINNED)."
  exit 0
fi

echo "==> $UPSTREAM_BRANCH is at $LATEST; Conduit is pinned to $PINNED."
git -C "$UPSTREAM_DIR" log --oneline -n 20 "$LATEST" 2>/dev/null || true
echo ""
echo "    To move the pin, set UPSTREAM_COMMIT=$LATEST in scripts/setup.sh and scripts/setup.ps1,"
echo "    update conduit.upstream.commit in gradle.properties, re-run setup and review overlays/."
