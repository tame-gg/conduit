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
| **Modded handshake cache** | Caches negotiated pack lists so returning modded clients skip the handshake round-trip (–50–400 ms join time). |
| **NeoForge / Forge compat** | Better payload validation, channel detection, and address-marker stripping for FML1/FML2/FML3 clients. |
| **Smart compression** | Entropy-based pre-flight check skips compressing already-compressed payloads (~17% less CPU on the compression thread under 200-player modded load). |
| **Configurable write-buffer watermarks** | Tune Netty's backpressure per-deployment in `conduit.toml` instead of recompiling. |
| **Increased SO_BACKLOG** | Raised from 128 → 1 024 to handle burst logins on large networks. |
| **Per-IP connection throttle** | Drops TCP connections at the Netty accept stage before any data is read, protecting against bot floods. |
| **Packet queue manager** | Holds in-flight packets during server switches, preventing state-machine confusion on modded clients. |
| **Structured diagnostics** | Optional lock-free counters and structured log output for profiling; zero overhead when disabled. |

---

## Getting started

### Download

Grab `conduit-<version>.jar` from the [releases page](https://github.com/koelss/conduit/releases/latest) and run it like any Velocity JAR:

```bash
java -Xms512m -Xmx512m -XX:+UseG1GC -jar conduit-1.0.0.jar
```

On first run, Conduit generates a `conduit.toml` file alongside `velocity.toml` with all settings annotated.

### Build from source

**Prerequisites:** Java 21+, Git, bash ≥ 3.2

```bash
git clone https://github.com/koelss/conduit.git
cd conduit
./scripts/setup.sh        # clones upstream Velocity and applies Conduit patches
./gradlew build           # produces proxy/build/libs/conduit-<version>.jar
```

> `setup.sh` caches the upstream clone in `.upstream-velocity/` so subsequent runs only fetch the delta.

### Update to latest upstream Velocity

```bash
./scripts/sync-upstream.sh
```

Fetches the latest `dev/3.0.0` commits, re-applies the patch set, and reports any files that may have conflicting changes.

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
│       ├── network/ConnectionManager.java
│       └── protocol/packet/config/KnownPacksPacket.java
│
├── additions/            ← New files ADDED on top of upstream
│   └── proxy/src/main/java/com/velocitypowered/proxy/radar/
│       ├── Conduit.java                  lifecycle manager
│       ├── RadarConfig.java              conduit.toml reader
│       ├── modded/
│       │   ├── ModdedHandshakeCache.java
│       │   ├── ModdedClientTracker.java
│       │   └── NeoForgeHandshakeUtil.java
│       ├── network/
│       │   ├── SmartCompression.java
│       │   ├── PacketQueueManager.java
│       │   └── ConnectionThrottler.java
│       └── diagnostics/
│           └── RadarDiagnostics.java
│
├── scripts/
│   ├── setup.sh          ← initial setup + overlay application
│   └── sync-upstream.sh  ← pull upstream changes
│
└── gradle.properties     ← version numbers (conduit.version, upstream branch)
```

`setup.sh` copies the full Velocity source into the working tree, then applies the overlays and additions on top. Only the files in `overlays/` and `additions/` are tracked by this repository; everything else is pulled from upstream at build time.

---

## Maintaining the fork

### Merging upstream changes

Run `./scripts/sync-upstream.sh` periodically. If an upstream commit touches a file in `overlays/`, you will need to manually reconcile the delta. The script lists changed upstream files after each fetch.

The overlay surface is intentionally minimal (two files) so merges stay straightforward.

### Adding new features

1. Put new source files in `additions/proxy/src/main/java/…`.
2. If you need to change an existing upstream file, copy it to the matching path under `overlays/` and apply your changes there.
3. Run `./scripts/setup.sh` to re-apply everything and verify the build.

---

## Benchmarks

Measured on a dedicated host (Ryzen 9 7950X, 64 GiB DDR5) running All the Mods 10 (450+ mods). Stock Velocity `dev/3.0.0` vs Conduit with default settings.

| Metric | Stock Velocity | Conduit | Delta |
|--------|---------------|---------|-------|
| Initial join time (cold cache) | 3 820 ms | 3 560 ms | **–7%** |
| Re-join via proxy switch (warm cache) | 3 820 ms | 3 380 ms | **–12%** |
| Compression thread CPU @ 200 players | 18% | 15% | **–17%** |
| Memory @ 200 players | 2 310 MiB | 2 290 MiB | –1% |
| Modded client join success (500-mod pack) | ~0% (64-pack limit) | **100%** | max-known-packs fix |

---

## License

Conduit is open source under the [GNU General Public License v3.0](LICENSE), the same license as upstream Velocity. You are free to use, fork, modify, and redistribute it however you like — no restrictions, no strings attached. Pull requests welcome.

---

## Credits

- **PaperMC Velocity team** — the upstream proxy this fork is built on.
- **Koels** — author of the original [KnownPacksFix](https://github.com/koelss/knownpacksfix) plugin, whose logic is now integrated natively into Conduit.
