#!/usr/bin/env bash
# setup.sh — Clones upstream Velocity-CTD, applies Conduit overlays, and prepares the build tree.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

UPSTREAM_REPO="https://github.com/GemstoneGG/Velocity-CTD.git"
UPSTREAM_BRANCH="dev"
UPSTREAM_DIR="$ROOT_DIR/.upstream-velocity"
CI_MODE=false

for arg in "$@"; do
  [[ "$arg" == "--ci" ]] && CI_MODE=true
done

echo "==> Conduit setup"
echo "    Root:     $ROOT_DIR"
echo "    Upstream: $UPSTREAM_REPO @ $UPSTREAM_BRANCH"
echo ""

# ── 1. Clone or update upstream ──────────────────────────────────────────────
if [[ -d "$UPSTREAM_DIR/.git" ]]; then
  echo "==> Updating cached upstream clone..."
  git -C "$UPSTREAM_DIR" remote set-url origin "$UPSTREAM_REPO"
  git -C "$UPSTREAM_DIR" fetch --depth=1 origin "$UPSTREAM_BRANCH"
  git -C "$UPSTREAM_DIR" checkout FETCH_HEAD
else
  echo "==> Cloning upstream Velocity-CTD (depth=1)..."
  git clone --depth=1 --branch "$UPSTREAM_BRANCH" "$UPSTREAM_REPO" "$UPSTREAM_DIR"
fi

# ── 2. Copy upstream source into our project tree ────────────────────────────
echo "==> Syncing upstream source files..."

for module in api native proxy luckperms-integration build-logic config; do
  if [[ -d "$UPSTREAM_DIR/$module" ]]; then
    rsync -a --delete \
      --exclude='**/KnownPacksPacket.java' \
      --exclude='**/ConnectionManager.java' \
      --exclude='**/VelocityServer.java' \
      --exclude='**/ServerChannelInitializer.java' \
      --exclude='**/HandshakeSessionHandler.java' \
      "$UPSTREAM_DIR/$module/" "$ROOT_DIR/$module/"
  fi
done

# Copy upstream-owned Gradle support files into the generated working tree.
for f in gradlew gradlew.bat gradle HEADER.txt HEADER-CTD.txt; do
  if [[ -e "$UPSTREAM_DIR/$f" ]]; then
    rm -rf "$ROOT_DIR/$f"
    cp -r "$UPSTREAM_DIR/$f" "$ROOT_DIR/$f"
  fi
done
chmod +x "$ROOT_DIR/gradlew" 2>/dev/null || true

# ── 3. Apply Conduit overlays (modified upstream files) ─────────────────
echo "==> Applying overlays (modified upstream files)..."
if [[ -d "$ROOT_DIR/overlays" ]]; then
  rsync -a "$ROOT_DIR/overlays/" "$ROOT_DIR/"
  echo "    Overlays applied."
fi

# ── 4. Apply Conduit additions (new files) ─────────────────────────────
echo "==> Applying additions (new Conduit files)..."
if [[ -d "$ROOT_DIR/additions" ]]; then
  rsync -a "$ROOT_DIR/additions/" "$ROOT_DIR/"
  echo "    Additions applied."
fi

# ── 5. Write the Conduit build metadata ──────────────────────────────────────
CONDUIT_VERSION=$(grep "^conduit.version=" "$ROOT_DIR/gradle.properties" | cut -d= -f2)
if [[ -z "$CONDUIT_VERSION" ]]; then
  echo "ERROR: conduit.version not found in gradle.properties" >&2
  exit 1
fi
BUILD_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
GIT_HASH=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")

mkdir -p "$ROOT_DIR/proxy/src/main/resources/com/velocitypowered/proxy/conduit"
cat > "$ROOT_DIR/proxy/src/main/resources/com/velocitypowered/proxy/conduit/conduit-build.properties" <<EOF
conduit.version=$CONDUIT_VERSION
conduit.build.time=$BUILD_TIME
conduit.git.hash=$GIT_HASH
conduit.upstream.branch=$UPSTREAM_BRANCH
EOF

echo "==> Setup complete. Run './gradlew build' to compile."
