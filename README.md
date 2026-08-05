# Conduit

> A performance-focused, modded-network-ready fork of [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD).

Conduit is built directly on Velocity-CTD `libdeflate` and layers Conduit’s modded-network
optimizations on top of CTD’s Redis, queue, command, and LuckPerms integration work. It remains
compatible with existing Velocity plugins and targets Paper, Spigot, Fabric, Forge, and NeoForge
backends.

**[Download the latest release →](https://github.com/tame-gg/conduit/releases/latest)**

---

## Key features

| Feature | Description |
|---------|-------------|
| **Configurable known-packs limit** | Raises the max-known-packs cap via `conduit.toml`. Default 1 024 vs Velocity's 64 — no reflection hacks. Replaces the KnownPacksFix plugin. |
| **Modded handshake cache** | Caches negotiated pack lists so returning modded clients skip the handshake round-trip. |
| **NeoForge / Forge compat** | Better payload validation, channel detection, and address-marker stripping for FML1/FML2/FML3 clients. |
| **Smart compression** | Entropy-based pre-flight compression path avoids unsafe raw above-threshold packets and falls back to vanilla compression when needed. |
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
| **Attack mode** | `/conduit attackmode on/off/status` applies stricter live flood-mitigation limits without editing config. |
| **Maintenance mode** | `/conduit maintenance on/off/status` closes the network to non-exempt players with a custom kick message and maintenance MOTD. Bypass via permission or username allow-list; state survives restarts. Replaces standalone Maintenance plugins. |
| **Mod compatibility routing** | Optional per-backend allow rules for Vanilla, Fabric, Forge, NeoForge, and unknown modded clients. |
| **Tab-complete cache** | Short-TTL LRU cache for backend tab-completion responses keyed on (server, prefix). Absorbs key-held tab spam at near-zero CPU. |
| **Metrics JSON endpoint** | Optional loopback HTTP endpoint and `/conduit metrics json` expose diagnostics counters for dashboards. |
| **Structured diagnostics** | Optional lock-free counters and structured log output for profiling; zero overhead when disabled. |
| **Bundled spark profiler** | Ships the official `lucko/spark` Velocity plugin and installs it as `/sparkv` / `/sparkvelocity`. Skips if an operator-managed spark jar is present, and can be disabled via `conduit.toml → [spark] → bundle-enabled`. |
| **Native LuckPerms** | Ships the official LuckPerms Velocity plugin and installs it on first run, so permissions, groups, and prefixes work out of the box. Skips if an operator-managed LuckPerms jar is present, and can be disabled via `conduit.toml → [luckperms] → bundle-enabled`. CTD's LuckPerms permission resolver then activates automatically. |
| **Update checker** | Asynchronously checks GitHub Releases for a newer Conduit version, caches the result, compares semantic versions, and tells `conduit.update.notify` staff how many releases they are behind on join. Modular provider design; configurable via `conduit.toml → [update]`. |
| **Command forwarding** | Optional backend→proxy command execution over plugin messaging, wire-compatible with the VelocityCommandForward plugin. A backend's `/proxyexec <cmd>` runs on the proxy as console or the forwarding player. Off by default; enable via `conduit.toml → [forwarding] → command-forwarding`. Replaces the proxy-side VelocityCommandForward plugin. |
| **Self-updating config** | `conduit.toml` is topped up on every start: options added in newer Conduit versions appear automatically with documented defaults, existing values and comments are preserved, and no manual delete/regenerate is ever needed. |
| **Operator commands** | `/conduit reload \| diagnostics \| health \| doctor \| unblock <ip> \| cache invalidate <ip>` and `/modlist [player]` — no extra plugin needed. |

---

## Getting started

### Download

Grab the release jar from the [releases page](https://github.com/tame-gg/conduit/releases/latest) and run it like any Velocity JAR:

```bash
java -Xms512m -Xmx512m -XX:+UseG1GC -jar velocity-proxy-3.5.0-CONDUIT-SNAPSHOT-all.jar
```

On first run, Conduit generates a `conduit.toml` file alongside `velocity.toml` with all settings annotated.

### Build from source

**Prerequisites:** Java 25+, Git

#### macOS / Linux

```bash
git clone https://github.com/tame-gg/conduit.git
cd conduit
./scripts/setup.sh        # clones upstream Velocity-CTD and applies Conduit patches
./gradlew build           # produces proxy/build/libs/velocity-proxy-<version>-all.jar
```

#### Windows (PowerShell)

```powershell
git clone https://github.com/tame-gg/conduit.git
cd conduit
.\scripts\setup.ps1       # clones upstream Velocity-CTD and applies Conduit patches
.\gradlew.bat build       # produces proxy\build\libs\velocity-proxy-<version>-all.jar
```

> The setup script caches the upstream clone in `.upstream-velocity/` so subsequent runs only fetch the delta.

### Update to latest upstream Velocity-CTD

#### macOS / Linux

```bash
./scripts/sync-upstream.sh
```

#### Windows

```powershell
# Fetch new upstream commits, then re-run setup
git -C .upstream-velocity fetch origin libdeflate
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
channel-guard-preset            = "custom"  # custom | audit | modded-safe | strict
channel-guard-action            = "drop"    # drop | kick | log
channel-guard-block-list        = [         # case-insensitive; trailing ':' matches namespace
    "wdl:init", "wdl:control", "wdl:request",
    "world_downloader:init", "world_downloader:control", "world_downloader:request",
    "xaero:", "schematica:", "bsm:", "5zig:",
]
attack-mode-connection-throttle-max-per-second = 8
attack-mode-bot-filter-threshold = 3
attack-mode-motd-cache-ttl-ms = 10000

[routing]
mod-compatibility = []          # e.g. ["lobby=VANILLA,FABRIC", "modded=NEOFORGE,LEGACY_FORGE"]

[metrics]
http-enabled = false
http-host = "127.0.0.1"
http-port = 9589
http-path = "/metrics"

[maintenance]
enabled         = true      # register the maintenance listeners
active-on-start = false     # start the proxy already in maintenance
kick-message    = "<red>The network is currently down for maintenance.\n<gray>Please check back soon."
motd            = "<red><bold>⚠ Maintenance</bold></red>\n<gray>The network is temporarily offline."
allowlist       = []        # usernames always allowed in during maintenance

[commands]
admin-enabled                   = true      # registers /conduit (permission: conduit.admin)
modlist-enabled                 = true      # registers /modlist  (permission: conduit.modlist)

[forwarding]
command-forwarding              = false     # opt-in; backend → proxy command execution
channel                         = "velocity_command_forward:main"  # must match backend plugin
require-permission              = false     # gate player-context commands on conduit.forward.execute
log-forwarded-commands          = true      # echo the backend log line to the proxy console

[spark]
bundle-enabled                  = true      # extract bundled spark plugin; set false to suppress

[luckperms]
bundle-enabled                  = true      # extract bundled LuckPerms plugin; set false to suppress
```

### Operator commands

| Command | What it does | Permission |
|---------|--------------|------------|
| `/conduit reload` | Re-reads `conduit.toml` and applies live-tunable values (handshake TTL, throttle rate, diagnostics flags). | `conduit.admin` |
| `/conduit diagnostics` | Prints the counter snapshot — connections, cache hits, throttles, slow logins, channels blocked, etc. | `conduit.admin` |
| `/conduit health` | Prints the per-backend health summary (`HEALTHY` / `UNHEALTHY`, failure count, last-checked timestamp). | `conduit.admin` |
| `/conduit doctor` | Checks Conduit config and feature wiring, including fallback-server names and restart-required/experimental settings. | `conduit.admin` |
| `/conduit metrics json` | Prints diagnostics counters as compact JSON. | `conduit.admin` |
| `/conduit attackmode on \| off \| status` | Applies or restores stricter live flood-mitigation limits. | `conduit.admin` |
| `/conduit maintenance on \| off \| status` | Toggles network-wide maintenance mode. Non-exempt players are rejected; state persists across restarts. | `conduit.admin` |
| `/conduit config diff` | Shows changed config keys and whether they apply live or require restart. | `conduit.admin` |
| `/conduit failover test <server>` | Shows which healthy fallback server would be selected if a backend failed. | `conduit.admin` |
| `/conduit unblock <ip>` | Clears a bot-filter block on the given IP. | `conduit.admin` |
| `/conduit cache invalidate <ip>` | Drops cached MOTD and modded-handshake entries for the given IP. | `conduit.admin` |
| `/modlist` | Lists every connected player with their detected mod loader and channel count. | `conduit.modlist` |
| `/modlist <player>` | Shows the detailed channel and known-pack list for one player. Tab-completes player names. | `conduit.modlist` |
| `/sparkv` | Runs the bundled spark Velocity profiler. Alias: `/sparkvelocity`. | `spark.*` command permissions |

The `conduit.channelguard.bypass` permission exempts staff accounts from `ChannelGuard` blocks.
The `conduit.maintenance.bypass` permission lets staff connect while maintenance mode is active.
The `conduit.update.notify` permission makes staff see the update notice on join (see below).

Conduit publishes these `conduit.*` nodes into LuckPerms' suggestion tree at startup (when LuckPerms
is present), so they autocomplete in `/lpv` and the web editor even though Velocity has no
permission registry. Grant them like any other node, e.g.
`/lpv user <name> permission set conduit.maintenance.bypass true`.

### Update notifications

Conduit checks [GitHub Releases](https://github.com/tame-gg/conduit/releases) for a newer version
and lets your staff know when one is out. The check is designed to stay out of the way:

- It runs **asynchronously** after startup and **never blocks** the proxy from accepting players.
- Results are **cached** (6 hours by default) and the check **fails quietly** if GitHub is
  unreachable or rate-limited — it never throws into a login or a command.
- Versions are compared using **semantic versioning**, and **pre-releases are ignored** unless the
  build you are running is itself a pre-release.

When an update is available, players holding the `conduit.update.notify` permission see a one-line
notice on join — the running version, the latest version, exactly how many releases behind you are
(e.g. *"You are 5 release(s) behind"*), and a clickable link to the release. A single summary is
also written to the console at startup, and `/velocity info` reflects the same status. Ordinary
players never see any of this.

Configure it under `[update]` in `conduit.toml`:

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Master switch for the whole update checker. |
| `notify-on-startup` | `true` | Log a single update summary to the console at startup. |
| `notify-on-join` | `true` | Notify `conduit.update.notify` holders when they join. |
| `github-repository` | `tame-gg/conduit` | The `owner/name` repository whose Releases are compared. |
| `include-prereleases` | `false` | Treat pre-releases as upgrade targets (forced on for pre-release builds). |
| `cache-minutes` | `360` | How long a result is reused before a background refresh. |

The checker is provider-based (`UpdateProvider` → `GitHubReleaseProvider`), so an alternative update
source can be added later without changing the comparison or notification logic.

### Command forwarding

Conduit can run commands that a **backend** server forwards to the proxy — for example so a Discord
bot, or a plugin like TAB that needs proxy-level commands, can trigger `/send`, `/alert`, or any
other proxy command from a Paper/Spigot server. This is a built-in, opt-in re-implementation of the
proxy half of the [VelocityCommandForward](https://github.com/ItsTauTvyDas/VelocityCommandForward)
plugin, and it speaks the **same plugin-messaging protocol**, so you keep using that project's
**backend** plugin and simply stop installing its Velocity plugin.

- **Disabled by default.** With `command-forwarding = false` (the default) Conduit registers no
  channel and does nothing, so existing installs are unaffected until you opt in.
- Enable it under `[forwarding]` in `conduit.toml`, then install only the *backend* half of
  VelocityCommandForward on your servers. A backend player or console runs `/proxyexec <command>`;
  the command executes on the proxy as the **proxy console** (console-originated) or as the
  **forwarding player** (player-originated, if still online).
- **Only real backend connections are honoured** — a client cannot forge these messages to execute
  commands. Set `require-permission = true` to additionally require the forwarding player to hold
  `conduit.forward.execute`; console-originated commands are always allowed.

| Key | Default | Description |
|-----|---------|-------------|
| `command-forwarding` | `false` | Master switch. Off preserves current behaviour. |
| `channel` | `velocity_command_forward:main` | Plugin-messaging channel; must match the backend plugin. |
| `require-permission` | `false` | Require `conduit.forward.execute` for player-context commands. |
| `log-forwarded-commands` | `true` | Echo the backend-supplied log line to the proxy console. |

> **Note:** plugin messaging needs at least one player online for a console-originated backend
> command to reach the proxy — a Minecraft limitation, not a Conduit one.

### Automatic `conduit.toml` updates

You never have to delete or regenerate `conduit.toml` after upgrading Conduit. On every start Conduit
compares your file against the defaults bundled in the jar and **adds any options a newer version
introduced**, each with its documented default value and comment. Your existing values and comments
are **never overwritten** — an option you already set (even to the default) is left untouched — and a
file that is already complete is not rewritten at all. Structural renames between versions are
handled by an internal migration table so a moved option carries your value across instead of
resetting. The result is logged, e.g. `conduit.toml: added 4 new option(s) with defaults: …`.

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
├── overlays/             ← Files that REPLACE upstream Velocity-CTD files
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

The setup script copies the full Velocity-CTD source into the working tree, then applies the overlays and additions on top. Only the files in `overlays/` and `additions/` are tracked by this repository; everything else is pulled from upstream at build time.

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

Conduit is open source under the [GNU General Public License v3.0](LICENSE), the same license as upstream Velocity-CTD. You are free to use, fork, modify, and redistribute it however you like — no restrictions, no strings attached. Pull requests welcome.

---

## Credits

- **GemstoneGG Velocity-CTD team** — the upstream proxy this fork is built on.
- **PaperMC Velocity team** — the original Velocity project CTD is based on.
- **Koels** — author of the original [KnownPacksFix](https://github.com/koels/knownpacksfix) plugin, whose logic is now integrated natively into Conduit.
