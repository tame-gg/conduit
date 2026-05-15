# Conduit

> A performance-focused, modded-network-ready fork of [Velocity](https://github.com/PaperMC/Velocity) by PaperMC.

Conduit is built directly on the Velocity `dev/3.0.0` branch and adds native support for
heavily modded Minecraft networks without requiring external plugins. It is 100% API-compatible
with existing Velocity plugins and maintains full protocol compatibility with Paper, Spigot,
Fabric, Forge, and NeoForge backends.

**[Download the latest release →](https://github.com/koelss/conduit/releases/latest)**

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
| **Per-IP connection throttle** | Drops TCP connections at the Netty accept stage before any data is read, protecting against bot floods. |
| **Packet queue manager** | Holds in-flight packets during server switches, preventing state-machine confusion on modded clients. |
| **Backend health checking** | Pings all registered backends on a configurable interval and marks unhealthy servers so they are skipped by fallback routing. |
| **Fallback routing on kick** | Automatically redirects kicked players to a healthy fallback server instead of disconnecting them. |
| **MOTD caching** | Caches server list pings per IP to reduce repeated ping overhead. |
| **Graceful shutdown** | Transfers connected players to a fallback server (or disconnects with a friendly message) before the proxy exits. |
| **Bot filter** | Blocks IPs that repeatedly open connections without completing the login handshake. |
| **Structured diagnostics** | Optional lock-free counters and structured log output for profiling; zero overhead when disabled. |

---

## Getting started

### Download

Grab `conduit-<version>.jar` from the [releases page](https://github.com/koelss/conduit/releases/latest) and run it like any Velocity JAR:

```bash
java -Xms512m -Xmx512m -XX:+UseG1GC -jar conduit-1.2.1.jar
```

On first run, Conduit generates a `conduit.toml` file alongside `velocity.toml` with all settings annotated.

### Build from source

**Prerequisites:** Java 21+, Git

#### macOS / Linux

```bash
git clone https://github.com/koelss/conduit.git
cd conduit
./scripts/setup.sh        # clones upstream Velocity and applies Conduit patches
./gradlew build           # produces proxy/build/libs/conduit-<version>.jar
```

#### Windows (PowerShell)

```powershell
git clone https://github.com/koelss/conduit.git
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
```

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
│       └── protocol/packet/config/KnownPacksPacket.java
│
├── additions/            ← New files ADDED on top of upstream
│   └── proxy/src/main/java/com/velocitypowered/proxy/conduit/
│       ├── Conduit.java                  lifecycle manager
│       ├── ConduitConfig.java            conduit.toml reader
│       ├── modded/
│       │   ├── ModdedHandshakeCache.java
│       │   ├── ModdedClientTracker.java
│       │   └── NeoForgeHandshakeUtil.java
│       ├── network/
│       │   ├── SmartCompression.java
│       │   ├── PacketQueueManager.java
│       │   └── ConnectionThrottler.java
│       ├── health/
│       │   ├── BackendHealthChecker.java
│       │   └── FallbackRouter.java
│       ├── motd/
│       │   └── MotdCache.java
│       ├── security/
│       │   └── BotFilter.java
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
