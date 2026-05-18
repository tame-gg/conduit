# Conduit

> A performance-focused, modded-network-ready fork of [Velocity](https://github.com/PaperMC/Velocity) by PaperMC.

Conduit is built directly on the Velocity `dev/3.0.0` branch and adds native support for
heavily modded Minecraft networks without requiring external plugins. It is 100% API-compatible
with existing Velocity plugins and maintains full protocol compatibility with Paper, Spigot,
Fabric, Forge, and NeoForge backends.

**[Download the latest release →](https://github.com/tame-gg/conduit/releases/latest)**

---

## Key features

| Feature | Description |
|---------|-------------|
| **Configurable known-packs limit** | Raises the max-known-packs cap via `conduit.toml`. Default 1 024 vs Velocity's 64 — no reflection hacks. Replaces the KnownPacksFix plugin. |
| **Modded handshake cache** | Caches negotiated pack lists so returning modded clients skip the handshake round-trip. |
| **NeoForge / Forge compat** | Better payload validation, channel detection, and address-marker stripping for FML1/FML2/FML3 clients. |
| **Smart compression** | Entropy-based pre-flight check skips compressing already-compressed payloads. |
| **Configurable write-buffer watermarks** | Tune Netty's backpressure per-deployment in `conduit.toml` instead of recompiling. |
| **Increased SO_BACKLOG** | Raised from 128 → 1 024 to handle burst logins on large networks. |
| **Per-IP connection throttle** | Drops TCP connections at the Netty accept stage before any packet data is read, protecting against bot floods. |
| **Packet queue manager** | Holds in-flight packets during server switches, preventing state-machine confusion on modded clients. |
| **Backend health checking** | Pings all registered backends on a configurable interval and marks unhealthy servers so they are skipped by fallback routing. |
| **Fallback routing on kick** | Automatically redirects kicked players to a healthy fallback server instead of disconnecting them. |
| **MOTD caching** | Caches server list pings per IP to reduce repeated ping overhead. |
| **Graceful shutdown** | Transfers connected players to a fallback server (or disconnects with a friendly message) before the proxy exits. |
| **Bot filter** | Blocks IPs that repeatedly open TCP channels without completing the initial Minecraft handshake. |
| **Channel guard** | Intercepts known cheat / exploit plugin-message channels (World-Downloader, X-Ray clients) and applies a drop / kick / log policy. |
| **Tab-complete cache** | Short-TTL LRU cache for backend tab-completion responses keyed on (server, prefix). Absorbs key-held tab spam at near-zero CPU. |
| **Structured diagnostics** | Optional lock-free counters and structured log output for profiling; zero overhead when disabled. |
| **Bundled spark profiler** | Ships the official `lucko/spark` Velocity plugin and installs it as `/sparkv` / `/sparkvelocity`. Skips if an operator-managed spark jar is present, and can be disabled via `conduit.toml → [spark] → bundle-enabled`. |
| **Operator commands** | `/conduit reload \| diagnostics \| health \| doctor \| unblock <ip> \| cache invalidate <ip>` and `/modlist [player]` — no extra plugin needed. |

---

## Getting started

### Download

Grab `conduit-<version>.jar` from the [releases page](https://github.com/tame-gg/conduit/releases/latest) and run it like any Velocity JAR:

```bash
java -Xms512m -Xmx512m -XX:+UseG1GC -jar conduit-1.3.0.jar
```

On first run, Conduit generates a `conduit.toml` file alongside `velocity.toml` with all settings annotated.

### Build from source

**Prerequisites:** Java 21+, Git

#### macOS / Linux

```bash
git clone https://github.com/tame-gg/conduit.git
cd conduit
./scripts/setup.sh        # clones upstream Velocity and applies Conduit patches
./gradlew build           # produces proxy/build/libs/conduit-<version>.jar
```

#### Windows (PowerShell)

```powershell
git clone https://github.com/tame-gg/conduit.git
cd conduit
.\scripts\setup.ps1       # clones upstream Velocity and applies Conduit patches
.\gradlew.bat build       # produces proxy\build\libs\conduit-<version>.jar
```

> The setup script caches the upstream clone in `.upstream-velocity/` so subsequent runs only fetch the delta.

### Update to latest upstream Velocity

#### macOS / Linux

```bash
./scripts/sync-upstream.sh
```

#### Windows

```powershell
# Fetch new upstream commits, then re-run setup
git -C .upstream-velocity fetch origin dev/3.0.0
git -C .upstream-velocity checkout FETCH_HEAD
.\scripts\setup.ps1
```

---

## Configuration

Conduit generates `conduit.toml` in your proxy directory on first run. Full annotated example:

```toml
[modded]
max-known-packs          = 1024     # raise for large modpacks; vanilla cap is 64
handshake-cache          = true
handshake-cache-ttl      = 300      # seconds
handshake-timeout-ms     = 30000
neoforge-compat          = true
legacy-forge-compat      = true
announce-modded-in-ping  = false
log-mod-handshakes       = false

[network]
write-buffer-high-watermark        = 2097152   # 2 MiB
write-buffer-low-watermark         = 1048576   # 1 MiB
smart-compression                  = true
smart-compression-min-delta        = 64        # minimum bytes saved to justify compressing
packet-queue-optimization          = true
packet-queue-max-depth             = 256
connection-throttle                = true
connection-throttle-max-per-second = 30
tab-complete-cache                 = false     # opt-in; integrates via overlay (see CHANGES)
tab-complete-cache-ttl-ms          = 1500
tab-complete-cache-max-entries     = 1024

[diagnostics]
enabled                      = false
trace-mod-handshakes         = false    # very verbose — debug only
slow-connection-threshold-ms = 3000

[server]
health-check-enabled            = true
health-check-interval-ms        = 10000
fallback-servers                = []        # ordered list of preferred fallback server names
motd-cache-enabled              = true
motd-cache-ttl-ms               = 2000
graceful-shutdown-enabled       = true
graceful-shutdown-timeout-ms    = 5000
graceful-shutdown-message       = "Proxy is restarting. Please reconnect in a moment."
bot-filter-enabled              = true
bot-filter-timeout-ms           = 3000
bot-filter-threshold            = 10

[security]
channel-guard                   = false     # opt-in; default-off blocks player traffic
channel-guard-action            = "drop"    # drop | kick | log
channel-guard-block-list        = [         # case-insensitive; trailing ':' matches namespace
    "wdl:init", "wdl:control", "wdl:request",
    "world_downloader:init", "world_downloader:control", "world_downloader:request",
    "xaero:", "schematica:", "bsm:", "5zig:",
]

[commands]
admin-enabled                   = true      # registers /conduit (permission: conduit.admin)
modlist-enabled                 = true      # registers /modlist  (permission: conduit.modlist)

[spark]
bundle-enabled                  = true      # extract bundled spark plugin; set false to suppress
```

### Operator commands

| Command | What it does | Permission |
|---------|--------------|------------|
| `/conduit reload` | Re-reads `conduit.toml` and applies live-tunable values (handshake TTL, throttle rate, diagnostics flags). | `conduit.admin` |
| `/conduit diagnostics` | Prints the counter snapshot — connections, cache hits, throttles, slow logins, channels blocked, etc. | `conduit.admin` |
| `/conduit health` | Prints the per-backend health summary (`HEALTHY` / `UNHEALTHY`, failure count, last-checked timestamp). | `conduit.admin` |
| `/conduit doctor` | Checks Conduit config and feature wiring, including fallback-server names and restart-required/experimental settings. | `conduit.admin` |
| `/conduit unblock <ip>` | Clears a bot-filter block on the given IP. | `conduit.admin` |
| `/conduit cache invalidate <ip>` | Drops cached MOTD and modded-handshake entries for the given IP. | `conduit.admin` |
| `/modlist` | Lists every connected player with their detected mod loader and channel count. | `conduit.modlist` |
| `/modlist <player>` | Shows the detailed channel and known-pack list for one player. Tab-completes player names. | `conduit.modlist` |
| `/sparkv` | Runs the bundled spark Velocity profiler. Alias: `/sparkvelocity`. | `spark.*` command permissions |

The `conduit.channelguard.bypass` permission exempts staff accounts from `ChannelGuard` blocks.

### Migrating from KnownPacksFix

If you were previously using the [KnownPacksFix](https://github.com/koelss/knownpacksfix) plugin:

1. Remove the plugin JAR from your `plugins/` directory.
2. Set `max-known-packs` in `conduit.toml` to match your old `config.yml` `pack-limit` value.
3. Restart the proxy.

The `-Dvelocity.max-known-packs=<n>` JVM flag is still honoured and overrides `conduit.toml`, so existing start scripts with that flag continue to work unchanged.

---

## Architecture

```
conduit/
├── overlays/             ← Files that REPLACE upstream Velocity files
│   └── proxy/src/main/java/com/velocitypowered/proxy/
│       ├── VelocityServer.java           Conduit.init() wiring, branding
│       ├── network/ConnectionManager.java
│       ├── network/ServerChannelInitializer.java
│       ├── connection/client/HandshakeSessionHandler.java
│       └── protocol/packet/config/KnownPacksPacket.java
│
├── additions/            ← New files ADDED on top of upstream
│   └── proxy/src/main/java/com/velocitypowered/proxy/conduit/
│       ├── Conduit.java                  lifecycle manager
│       ├── ConduitConfig.java            conduit.toml reader
│       ├── command/
│       │   ├── ConduitCommand.java       /conduit admin command
│       │   └── ModListCommand.java       /modlist [player] command
│       ├── modded/
│       │   ├── ModdedHandshakeCache.java
│       │   ├── ModdedClientTracker.java
│       │   ├── ModTrackerListener.java   populates the tracker from REGISTER messages
│       │   └── NeoForgeHandshakeUtil.java
│       ├── network/
│       │   ├── SmartCompression.java
│       │   ├── PacketQueueManager.java
│       │   ├── ConnectionThrottler.java
│       │   └── TabCompleteCache.java
│       ├── health/
│       │   ├── BackendHealthChecker.java
│       │   └── FallbackRouter.java
│       ├── motd/
│       │   └── MotdCache.java
│       ├── security/
│       │   ├── BotFilter.java
│       │   └── ChannelGuard.java         drops WDL / X-Ray / cheat-mod channels
│       ├── shutdown/
│       │   └── GracefulShutdown.java
│       └── diagnostics/
│           └── ConduitDiagnostics.java
│
├── scripts/
│   ├── setup.sh          ← initial setup (macOS / Linux)
│   ├── setup.ps1         ← initial setup (Windows)
│   └── sync-upstream.sh  ← pull upstream changes (macOS / Linux)
│
└── gradle.properties     ← version numbers (conduit.version, upstream branch)
```

The setup script copies the full Velocity source into the working tree, then applies the overlays and additions on top. Only the files in `overlays/` and `additions/` are tracked by this repository; everything else is pulled from upstream at build time.

---

## Maintaining the fork

### Merging upstream changes

Run `./scripts/sync-upstream.sh` (or the manual PowerShell equivalent) periodically. If an upstream commit touches a file in `overlays/`, you will need to manually reconcile the delta. The script lists changed upstream files after each fetch.

The overlay surface is intentionally small so merges stay straightforward.

### Adding new features

1. Put new source files in `additions/proxy/src/main/java/com/velocitypowered/proxy/conduit/…`.
2. If you need to change an existing upstream file, copy it to the matching path under `overlays/` and apply your changes there.
3. Add the filename to the `--exclude` / `/XF` list in both `setup.sh` and `setup.ps1` so the upstream rsync does not overwrite it.
4. Run the appropriate setup script to re-apply everything and verify the build.

---

## License

Conduit is open source under the [GNU General Public License v3.0](LICENSE), the same license as upstream Velocity. You are free to use, fork, modify, and redistribute it however you like — no restrictions, no strings attached. Pull requests welcome.

---

## Credits

- **PaperMC Velocity team** — the upstream proxy this fork is built on.
- **Koels** — author of the original [KnownPacksFix](https://github.com/koelss/knownpacksfix) plugin, whose logic is now integrated natively into Conduit.
