# Conduit — Change Log

All changes relative to upstream `GemstoneGG/Velocity-CTD @ libdeflate`.

---

## 1.8.0 — Correctness pass on flood mitigation, caching, health, and reload

### Fixed — tab-complete cache served one player's suggestions to another

* `TabCompleteCache` keyed entries on `(server, prefix)` only. Backends filter Brigadier
  suggestions by what the requesting player may run, and `TabCompleteEvent` listeners tailor them
  further, so an administrator typing `/` populated an entry that any player typing `/` within the
  TTL was then served — their command list, and their argument completions with it. The key now
  includes the player's UUID, entries are dropped when that player disconnects, and the shipped
  config comment says why. The feature is opt-in (`tab-complete-cache = false`), so only operators
  who turned it on were exposed.

### Fixed — the bot filter barely counted anything during an actual flood

* `BotFilter` kept one pending-handshake timestamp **per address**, but connections are per
  channel. A source opening connections back to back overwrote that slot on every accept, so when
  a timer fired it measured the newest connection's age, decided it was too young, and counted
  nothing — the harder the flood, the less it counted. One legitimate login also cleared the
  pending state of every other in-flight connection from the same address.
* Handshakes are now tracked per connection (`BotFilter.Attempt`, held in a channel attribute), so
  concurrent connections are counted independently and an attempt can only be counted once.
* The timeout task no longer requires the channel to still be open, which previously excused every
  bot that opened a channel and hung up before the timer; instead, an attempt is settled as soon as
  the connection speaks Minecraft at all — a handshake packet or a legacy ping. That is what keeps
  ordinary server-list pings, which never send Login Start, from being counted as incomplete.

### Fixed — one missed ping pulled a backend out of routing

* `BackendHealthChecker` marked a server unhealthy on the first failed ping, while tracking a
  `consecutiveFailures` count it never consulted. A GC pause longer than the per-ping timeout was
  enough to flap a backend out of fallback routing and back. Hysteresis is now applied on both
  edges: `health-check-failure-threshold` (default 3) consecutive failures to go down, and
  `health-check-success-threshold` (default 2) consecutive successes to come back. Set the failure
  threshold to 1 for the old behaviour.

### Fixed — the connection throttle logged one line per dropped connection

* A `logger.warn` per drop, on the Netty accept path, turned mitigation into synchronous log I/O at
  exactly the moment the proxy is under load. Drops are now aggregated per source and reported at
  most once per `connection-throttle-log-interval-ms` (default 5 s), with the suppressed count.

### Fixed — an idle client could wedge the metrics endpoint

* `ConduitMetricsServer` accepted and handled requests on one thread with no socket timeout, so a
  single connection that opened and sent nothing blocked `readLine()` forever and the endpoint
  stopped answering anyone — including the scraper. Requests now run on a small worker pool, every
  socket carries a 5 s read timeout, and request lines and header counts are bounded.

### Fixed — the MOTD cache could hand a client the answer built for someone else

* Entries were keyed on the remote address alone, ignoring the client's protocol version and the
  hostname it connected to — both of which routinely change the correct response (version-specific
  labels, per-forced-host MOTDs). Two clients behind one NAT, or one client pinging two of your
  hostnames, could swap answers. The key now covers all three, and invalidating an address drops
  every entry it holds.

### Fixed — fallback routing never fired for the case it exists for

* `FallbackRouter` bailed out unless `KickedFromServerEvent.kickedDuringServerConnect()` was
  `true` — which is the case where the player is still sitting safely on their current server and
  a move elsewhere failed. Velocity's answer there is to keep them put and tell them, which is
  already right; Conduit was overriding it and dragging a settled player away. Meanwhile the case
  operators actually configure `fallback-servers` for — the backend kicked them, died under them,
  or was unreachable for their initial login, all of which report `kickedDuringServerConnect()` as
  `false` — returned immediately and did nothing.
* The condition is inverted to match the feature's description. The router now leaves a player who
  still holds a server alone, and for a player who has lost theirs it supplies a target whenever
  Velocity is about to disconnect them, or when the server Velocity's own `try` deque picked is one
  the health checker knows is unhealthy or draining. A redirect Velocity chose to a routable server
  is left alone.
* Redirects are budgeted at 3 per player per 15 seconds. Two backends that are both refusing
  connections could otherwise bounce a player between them indefinitely; past the budget the real
  disconnect reason is allowed through.

### Added — IPv6-aware source grouping for the throttle and bot filter

* Both subsystems keyed on a full address, which is correct for IPv4 and useless for IPv6: a single
  `/64` holds 2^64 addresses, so rotating the host bits gave an attacker a fresh counter per
  connection and thrashed the bounded tracking tables at the same time. Sources are now grouped by
  `connection-throttle-ipv4-prefix` (default 32) and `connection-throttle-ipv6-prefix` (default 64).
  `/conduit unblock <ip>` clears the block on the source network covering that address.

### Added — Prometheus exposition and authentication on the metrics endpoint

* `metrics.prometheus-path` (default `/metrics/prometheus`) serves the same counters in the
  Prometheus text format, so the endpoint can be scraped without a bespoke exporter;
  `/conduit metrics prometheus` prints the same thing. `metrics.auth-token`, when set, requires
  `Authorization: Bearer <token>` on every request, which makes binding off loopback a deliberate
  choice rather than an open door.

### Added — backend draining

* `/conduit drain <server>` marks a backend closed to new players and moves the players already on
  it to a routable server; `/conduit undrain <server>` reopens it. Drained servers stay *healthy* —
  draining is a routing decision, not a health state — and `FallbackRouter` now asks
  `isRoutable(...)` (healthy **and** not draining) when it picks a target, including for players
  whose `ServerPreConnectEvent` targets a draining backend.
* Staff holding `conduit.drain.bypass` are exempt from both halves of draining: they may still
  connect to a draining backend, and the evacuation leaves them where they are. Draining exists so
  a backend can be restarted without disrupting players, and whoever is performing the restart is
  precisely the person who needs to be on it. The node is published to LuckPerms' suggestion tree
  like the other `conduit.*` nodes.

### Added — gates on backend → proxy command forwarding

* A console-context forwarded command runs with the proxy console's full authority, and "it came
  from a backend" is only as strong as the least-trusted backend on the network. `allowed-servers`
  restricts which backends may forward at all; `command-allowlist` / `command-denylist` restrict
  what they may run, matched on the root command word. All three default to empty, i.e. unchanged
  behaviour.
* The backend-supplied log line is flattened to a single line and length-capped before it is
  printed, so a backend cannot forge log entries that look like they came from the proxy.

### Changed — `/conduit reload` applies what it can and names what it cannot

* Disabled subsystems used to be do-nothing sentinel singletons chosen at boot, so enabling
  `bot-filter-enabled`, `channel-guard`, `motd-cache-enabled`, `tab-complete-cache`,
  `health-check-enabled`, `connection-throttle`, or `command-forwarding` and reloading did nothing,
  silently. Those seven subsystems now carry an `enabled` flag and are reconfigurable at runtime,
  and reload pushes the new values into all of them — thresholds, TTLs, prefixes, block-lists,
  fallback order, maintenance messages, and the check interval included.
* Reload no longer prints a fixed list of restart-required caveats (which was also wrong: it
  claimed the bot filter needed a restart while applying its threshold). It compares the two
  configs and reports the keys *you* changed that could not be applied — bound sockets, the
  shutdown hook, listener watermarks, bundled-plugin installers — and says nothing when there are
  none.

### Build

* Refreshed the pinned bundled plugins after upstream rotated both URLs: LuckPerms 5.5.71 → 5.5.84,
  spark 1.10.172 → 1.10.185.

---

## 1.7.5 — Seamless switch attribute isolation

### Fixed — creative mode reach followed the player into a survival server

* From 1.20.5 the extended reach of creative mode is not a client-side property of the game mode:
  the server adds `player.block_interaction_range` and `player.entity_interaction_range` modifiers
  to the player's attributes and syncs them to the client. The client keeps those modifiers until a
  server replaces the attribute, and a server only sends the attributes that differ from its own
  idea of a freshly joined player — a survival destination has nothing to send for interaction
  range. Logging in on a creative server and then switching seamlessly into a survival one
  therefore kept creative reach. It only cleared when a later switch happened to take the
  non-seamless path, which makes the client build a fresh player and with it a fresh set of
  attributes; that is why hopping to a third server and back "fixed" it.
* New `UpdateAttributesPacket` (clientbound update attributes, ids `0x6D`/`0x71`/`0x75`/`0x7C`/
  `0x81`/`0x83` from 1.20.2, 1.20.3, 1.20.5, 1.21.2, 1.21.9, and 26.1). `BackendPlaySessionHandler`
  records the attributes a backend sends for the player's own entity, and a seamless switch replays
  them from `stripPreviousServerHud` with their base values intact and **no** modifiers, which is
  exactly what removes the creative range bonus. The destination's own attributes arrive right
  after the switch and override the replay.
* The properties section is forwarded as raw bytes and parsed only on a best-effort basis: an
  attribute layout the proxy does not understand leaves the snapshot empty instead of dropping the
  connection. The attribute key is a VarInt registry id from 1.20.5 and an identifier string before
  that; modifier ids are namespaced identifiers from 1.21 and UUIDs before that.

---

## 1.7.4 — Configurable supported Minecraft versions

### Added — `[versions]` section in `conduit.toml`

* Operators can pin which Minecraft versions the network accepts and advertises. A contiguous
  range is given as `minimum`/`maximum`: leaving a bound blank makes that end open, and setting
  both to the same value pins one exact version. A set with gaps in it (`1.21`, `1.21.1`, and
  `1.21.11` but nothing between) is given as an explicit `allow` list, which takes precedence over
  the range when non-empty and is normalised to ascending order with duplicates dropped. Either
  form accepts version names (`"1.21.11"`) or raw protocol numbers (`"774"`), as strings or bare
  integers. Off by default — nothing changes until `enabled = true`.
* `VersionGate` rewrites the server-list ping for clients outside the range: the advertised
  `version.protocol` is deliberately one the client cannot be speaking, which is the vanilla
  "incompatible" signal, and `version.name` carries the configured label (`Conduit 1.21.11` by
  default). The entry therefore states which versions the network runs rather than implying it is
  offline. Clients inside the range are untouched and still see the normal MOTD, ping bars, and
  player count.
* The rewrite runs at `PostOrder.LAST`, after `MotdCache` stores the response at `PostOrder.LATE`,
  so the per-IP cache only ever holds the unmodified MOTD and two clients on different versions
  behind one address each get the right answer.
* A join attempt from outside the range is denied at `PreLoginEvent` — before authentication — with
  a MiniMessage message that names the accepted versions: "This network only allows players to join
  on version 1.21.11." for a pinned version, and the plural "…on versions 1.21.4–1.21.11." (or
  "…on versions 1.8–1.8.9, 1.21.11." for an allow list) otherwise. Both are configurable (`kick-message`, `kick-message-range`), with `{versions}`, `{min}`,
  and `{max}` placeholders.
* Unknown version names and inverted ranges are rejected at config load with an explanatory error.
  Protocols the proxy itself does not support are left to Velocity's own handling, so the setting
  can only ever narrow the supported set.
* The range is live-reloadable via `/conduit reload` and appears in `/conduit config diff`.

---

## 1.7.3 — Seamless switch entity state isolation

### Fixed — the player's own entity kept the previous server's visuals

* A seamless switch keeps the client's player entity alive, so everything driven by entity metadata
  was inherited from the server the player left. `EntityMetadataPacket.resetBreathAndSwim` only reset
  the entity flags and air, leaving arrows and bee stingers stuck in the player, potion particles,
  the powder-snow freeze overlay, an item-use or riptide animation, and a crawling, swimming, or
  sleeping pose. A destination server only sends metadata that differs from its own idea of a freshly
  joined player, so none of it was ever corrected.
* Replaced by `EntityMetadataPacket.resetPlayerState(entityId, version)`, which resets every
  `Entity` and `LivingEntity` field that drives a client-visible visual: flags (fire, sneaking,
  sprinting, swimming, invisibility, glowing, elytra), air ticks, pose, powder-snow freeze ticks,
  hand states, effect particles, effect ambience, arrow count, bee stinger count, and sleeping
  position.
* Indices 0–14 have been stable since 1.17, but the serializer ids have not: the particle-list
  serializer arrived in 1.20.5 and the compound-tag serializer was removed in 1.21.9, shifting the
  ids after it. The pose and particle-list serializers are therefore resolved per protocol version,
  and only the head of the serializer registry (byte, VarInt, boolean, optional position), which has
  never moved, is otherwise used.
* Momentum is dropped with `EntityVelocityPacket.stop` on clients before 1.21.2. From 1.21.2 the
  destination's spawn teleport carries velocity and resets it on its own.

---

## 1.7.2 — Seamless switch state leaks

### Fixed — weather followed the player across a seamless switch

* `ClientPlaySessionHandler#doSeamlessPlaySwitch` sent only `GameEventPacket.EVENT_END_RAINING`. That
  clears the client's "is it raining" flag but leaves the rain gradient at full — the client keeps the
  gradients separate and waits for the server to fade them out — so precipitation and the darkened
  sky kept rendering. A destination with clear weather never sends a weather update, so nothing
  corrected it.
* The new `clearWeather()` also sends `EVENT_RAIN_LEVEL_CHANGE` and `EVENT_THUNDER_LEVEL_CHANGE` with
  a level of `0.0`. A raining destination announces its own weather right after the switch, which
  overrides the reset.

### Fixed — status effects survived a seamless switch and could not be cleared

* Effect cleanup relied entirely on `trackedPlayerEffects`, so any effect the proxy did not observe
  being applied stayed on the client's HUD indefinitely. The destination server never granted it, so
  `/effect clear` there had nothing to remove.
* `ClientPlaySessionHandler#clearPlayerEffects` now removes the tracked effects *and* every vanilla
  effect that exists in each client able to switch seamlessly (`speed` through `darkness`; registry
  ids from 1.20.5, one-based legacy ids before that). The range deliberately stops at the 1.20.2
  effects: an id missing from the client's registry fails to decode and would drop the connection,
  and later additions remain covered by the tracked set.

### Fixed — dimension comparison ignored 1.20.2–1.20.4 clients

* Join Game and Respawn carry the dimension as a registry identifier on 1.20.2–1.20.4 and as a
  numeric dimension type id from 1.20.5, and the unused field is left at zero. Comparing only the
  numeric field made every 1.20.2–1.20.4 switch look like a same-dimension move, so cross-dimension
  switches were performed seamlessly and the client kept the old world's sky, lighting, and weather.
* `ClientPlaySessionHandler` now keys on a `dimensionKey(…)` string that picks whichever field
  actually carries the dimension. `RespawnPacket` gains a `getDimensionInfo()` accessor for it.

---

## 1.7.1 — Seamless switch settle delay & teleport sound

### Fixed — seamless switch "stuck until reconnect"

* A seamless switch (for example returning to the hub) previously completed instantaneously. If the
  player moved before the destination backend finished streaming them in, their input raced ahead of
  the not-yet-ready world and they became **stuck in place until they reconnected**.
* `ClientPlaySessionHandler#applySeamlessSwitchEffects` now pauses reading of the client's inbound
  packets for a short **settle window** (`[advanced] seamless-switch-settle-ms`, default 250 ms)
  right after the switch, so early movement is buffered until the new server has loaded the player
  in, then reading resumes. This removes the desync and softens the otherwise instantaneous switch.

### Fixed — stale defaults on existing installs now refresh

* **Copyright line.** `TranslationRegistryManager` copies `messages.properties` into `lang/` and its
  `migrateIfNeeded` was append-only, so the `/velocity` copyright stayed frozen at the old
  `Copyright 2018-<year> …` on any install created before the branding change. A tightly-scoped
  force-refresh (`FORCE_REFRESH_KEYS`) now rewrites just `velocity.command.version-copyright` from the
  shipped default when it differs — no other translation string is touched.
* **conduit.toml `[advanced]` comments.** `ConduitConfigMigrator` gains a scoped comment-refresh
  (`COMMENT_REFRESH_KEYS`) that re-syncs the `#` comment lines above the seamless-switch options to
  the shipped wording, preserving every value exactly. This is the one documented exception to the
  otherwise append-only, byte-for-byte-preserving contract. Covered by a new regression test.

### Fixed — F3 server brand stuck across seamless switch

* Since 1.20.2 the backend sends its server brand (the F3 debug-screen line, rendered as
  `"<server>" (Conduit)`) during the configuration phase. A seamless switch absorbs that phase in
  `SeamlessConfigSessionHandler`, which previously ignored plugin messages, so the destination brand
  was swallowed and F3 kept showing the previous server — you could be in the hub and still see
  `"Hardcore Waiting Room (Conduit)"`. The handler now recognises the `minecraft:brand` message,
  rewrites it (appending the proxy brand, exactly as `BackendPlaySessionHandler` does in Play) and
  forwards it to the client, which is still in Play. Other config-phase plugin messages remain
  dropped during a seamless switch.

### Fixed — stale title stuck across seamless switch

* A same-dimension seamless switch keeps the client in Play and never ran the post-JoinGame title
  reset (that path is gated on `!seamless`), so a title/subtitle set by the previous server — such as
  a hub's `"<server>" server` banner — persisted after the switch and made every server appear to
  have the same name. `stripPreviousServerHud` now also writes a `GenericTitlePacket` RESET, so both
  seamless paths clear the previous server's title/subtitle/action bar.

### Fixed — teleport sound was silent

* The switch sound was played via `player.playSound(...)`, which resolves the emitter against
  `getConnectedServer()`. During a seamless switch that reassignment happens *after*
  `handleBackendJoinGame` runs, so the sound targeted the previous server's entity id and the client
  heard nothing. `applySeamlessSwitchEffects` now writes a `ClientboundSoundEntityPacket` directly
  against the player's own client-visible entity id (`clientEntityId`), so it reliably plays.

### Added — configurable settle delay and switch sound (`[advanced]`)

* `seamless-switch-settle-ms` (default `250`, range `0–5000`; `0` restores the old instantaneous
  behaviour).
* `seamless-switch-sound-enabled` (default `true`), `seamless-switch-sound` (default
  `minecraft:entity.enderman.teleport`, the ender pearl teleport sound), `seamless-switch-sound-volume`
  (default `1.0`) and `seamless-switch-sound-pitch` (default `1.0`). A cue is played to the player on
  every play-state (seamless) server switch.
* All new options are live-reloadable and surfaced in `ConduitConfigDiff`.

---

## 1.7.0 — Experimental seamless server switches

### Added — seamless server switches (`[advanced] seamless-server-switches`)

* Opt-in experimental path for **Minecraft 1.20.2+** clients moving between **homogeneous** backends
  (compatible registries/datapacks, same protocol/game environment). Default **false**.
* When enabled and the destination reports the **same dimension**, the proxy absorbs the backend
  configuration phase (`SeamlessConfigSessionHandler`) while the client stays in Play: no Join Game /
  Respawn, old entities and tab-list entries are cleared, and the new backend streams into the
  existing world. Other cases fall back to the existing fast/safe switch.
* Known Packs replies use the **intersection** of packs the client reported at login and packs the
  destination requested. Add Entity velocity for 1.21.9+ is carried as raw bytes so the 1.21.9
  encoding is not re-interpreted as the old short vector.
* Seamless switches now **remove leftover scoreboard objectives and teams** from the client before
  the destination backend streams in, so plugins that recreate a `sidebar` objective no longer
  disconnect 1.20.2+ clients.
* Seamless switches now drop leftover **boss bars** (TAB RAM/TPS bars, including bars that only
  receive later UPDATE packets), apply the destination **gamemode** via a game-event packet, reset
  the client's **operator permission level** (so F3+F4 is not left enabled from a hub OP), clear
  leftover **status effects**, and **rewrite the local player entity ID** on damage, hurt animation,
  entity sounds, velocity, and metadata so destination hurt sounds play. The vanilla
  "waiting for chunks" / world-generation overlay is not forwarded after a seamless switch.
  Boss-bar UUIDs from packets held behind Join Game are tracked on replay, leftover bars are
  removed again shortly after the switch, and air/swim metadata is reset so bubble HUD does not
  linger. Seamless joins no longer wait on `ServerConnectedEvent` before unlocking movement;
  destination world packets are released after a short 300ms hold so the switch is not a one-frame
  snap. 1.21.4+ backends receive `player_loaded` immediately so they do not ignore input for a second.
  When the destination is a **different dimension** (for example Overworld → End limbo), Join Game
  and Respawn still run, but leftover boss bars and other HUD are stripped first because the client
  never entered configuration.
* **Credit:** based on the seamless server switching patch by **ohemilyy**
  (`b5a97c65eea43a1a3d5e21589b67e2888729e1e4`, `feat: seamless server switches`),
  `ohemilyy <ohemilyy@proton.me>`. Source files retain the original `@author Luna` attribution.

---

## 1.6.3 — LuckPerms permission suggestions & bundled jar refresh

### Fixed — maintenance bypass (and other conduit.* nodes) now autocomplete in LuckPerms

* Velocity has no permission registry. LuckPerms only suggests nodes it has seen checked (or that
  were inserted into its internal `PermissionRegistry`). `conduit.maintenance.bypass` is checked
  only while maintenance mode is active, so it never appeared for autofill in `/lpv` or the web
  editor. The same gap affects other rarely-checked nodes such as `conduit.forward.execute`.
* Added `ConduitPermissions` (canonical catalogue) and `LuckPermsPermissionSeeder`, which runs
  after plugins load and publishes every first-class `conduit.*` node into LuckPerms via
  `PermissionRegistry.insert` (with a console `hasPermission` fallback). Safe no-op when LuckPerms
  is absent. Seeded nodes: `conduit.admin`, `conduit.modlist`, `conduit.maintenance.bypass`,
  `conduit.channelguard.bypass`, `conduit.update.notify`, `conduit.forward.execute`.

### Fixed (build)

* **Bundled LuckPerms Velocity jar bumped `5.5.55` → `5.5.71`.** The LuckPerms download server prunes
  old build numbers, so the pinned build `1643` (`5.5.55`) began returning HTTP 404, breaking the
  `:velocity-proxy:downloadBundledLuckPerms` task and the whole build/CI. `conduit.luckperms.velocity.url`
  and `conduit.luckperms.velocity.sha256` in `gradle.properties` now point at build `1658` (`5.5.71`).
* **CI release "Rename JAR" step** no longer fails when the Conduit shadowJar is already named
  `conduit-<version>.jar` (it previously still globbed the upstream `velocity-proxy-*-all.jar`
  name and skipped publishing the GitHub Release).

---

## 1.5.0 — Command forwarding & self-updating config

### Added — native command forwarding (`com.velocitypowered.proxy.conduit.forward`)

* **Backend → proxy command execution**, an opt-in re-implementation of the proxy half of the
  [VelocityCommandForward](https://github.com/ItsTauTvyDas/VelocityCommandForward) plugin, so
  operators no longer install a separate Velocity plugin. `CommandForwarder` registers the
  `velocity_command_forward:main` plugin-messaging channel and, on a message from a genuine
  `ServerConnection`, executes the carried command as the proxy console (empty sender UUID) or as
  the forwarding player (if still online) via `CommandManager#executeAsync`.
* **Wire-compatible** with the upstream plugin: the same `UTF uuid / UTF command / byte flags /
  UTF log` payload is parsed, and the event is marked `handled()` so the payload is consumed at the
  proxy. Existing VelocityCommandForward **backend** installs keep working unchanged.
* **Safe by construction:** only real backend connections are honoured (a client cannot forge the
  message), malformed payloads are dropped with a warning, and an optional `require-permission`
  gate makes player-context commands require `conduit.forward.execute` (console commands, produced
  only by a trusted backend console, are always allowed).
* **New `[forwarding]` section** in `conduit.toml`: `command-forwarding` (default **false** —
  preserves current behaviour), `channel`, `require-permission` (default false), and
  `log-forwarded-commands` (default true). The channel is validated as `namespace:path` when the
  feature is enabled. Surfaced in the startup summary and `ConduitConfig`.

### Added — self-updating configuration (`ConduitConfigMigrator`)

* **`conduit.toml` is now forward-compatible.** On every load, Conduit compares the operator's file
  against the defaults bundled in the jar and **adds any missing options** — including whole new
  sections — carrying each new key's **default value and its comment**. Operators no longer delete
  or regenerate the file after an upgrade.
* **Existing values are never overwritten** (even values equal to the default), user comments are
  preserved through the TOML round-trip, and a file that is already complete is **not rewritten**
  (its bytes and mtime are untouched). Additions and renames are logged.
* **Structural migrations** are supported via an internal old-path → new-path rename table, applied
  before missing keys are filled so a moved/renamed option carries the operator's value across
  instead of silently resetting. The map is empty today and exists as the documented extension
  point for future changes. The one-time `radar.toml → conduit.toml` rename is unchanged.
* Best-effort: any error during migration leaves the file untouched and startup proceeds.

## 1.4.0 — Conduit branding & update checker

### Changed — the proxy now identifies itself as Conduit

* **Version/identity strings say “Conduit”.** `VelocityServer#getVersion` reports the implementation
  name `Conduit` (was `Conduit-CTD`) and vendor `Conduit Contributors` (was
  `Conduit, based on Velocity-CTD`). This flows through the startup banner (`Booting up Conduit …`),
  the server-list/ping identity, `/velocity info`, and the virtual-plugin descriptions. Package
  names, the `com.velocityctd` internal namespace, APIs, license/copyright headers and historical
  comments are deliberately left untouched.
* **Jar manifest metadata rebranded** in `overlays/proxy/build.gradle.kts`:
  `Implementation-Title = Conduit`, `Implementation-Vendor = Conduit Contributors`.
* **User-facing project links point at Conduit.** `VelocityServer.VELOCITY_URL` (the GitHub link in
  `/velocity info` and on the virtual plugin) and the bootstrap fat-jar download hint now reference
  `github.com/tame-gg/conduit`.
* **Console/command strings de-Velocity’d** where they identify the running software: the invalid-
  config error, the `/velocity reload` success/failure messages, and the `/velocity info` update
  line. The `velocity.toml` filename and the “Velocity forwarding” mode name are kept for
  compatibility.

### Changed — development-build message

* Removed the legacy upstream `VersionChecker` startup call in `Velocity.java`, which logged
  *“You are running a development build of Velocity-CTD”* and compared against Velocity-CTD’s
  releases. The `/velocity info` development-build notice now reads *“…development build of
  Conduit”* and is still gated on the genuine `ProxyVersion#isDevelopmentVersion()` runtime check —
  release builds never show it.

### Added — modular update checker (`com.velocitypowered.proxy.conduit.update`)

* Checks **GitHub Releases** for a newer Conduit version, fully asynchronously — it never blocks
  startup, caches its result (default 6 h), fails soft on network errors, respects GitHub rate
  limits, compares **semantic versions** correctly, and ignores pre-releases unless the running
  build is itself a pre-release.
* Notifies staff holding `conduit.update.notify` when they join (running version, latest version,
  whether outdated, exact number of releases behind, and a link to the release), and logs one
  console summary at startup.
* Deliberately **provider-based** (`UpdateProvider` → `GitHubReleaseProvider`) so another source can
  be added without touching the comparison or caching logic. Configurable via a new `[update]`
  section in `conduit.toml`.

---

## 1.3.5 — Minecraft 26.2 support

### Changed

* **Rebased onto current upstream Velocity-CTD.** Upstream retired the `dev` branch; Conduit now
  tracks the `libdeflate` line, which brings **Minecraft 26.2 (protocol 776)** and 26.1 support
  along with the rest of upstream's networking work. `scripts/setup.sh`, `scripts/setup.ps1`,
  `scripts/sync-upstream.sh` and `gradle.properties` now point at `libdeflate`.
* **Build now requires JDK 25.** Upstream moved its toolchain and javadoc source level to Java 25;
  Conduit's root toolchain and the CI workflow were bumped to match. Runtime still targets the same
  JVM upstream does.
* **Module layout follows upstream's reorganization.** The former `luckperms-integration` module
  became a `permission-integration` SPI (`permission-integration/spi`) plus a per-provider adapter
  (`permission-integration/luckperms`), and a new `bootstrap` module was added. `settings.gradle.kts`
  and the setup scripts were updated to sync and include these modules. Conduit no longer hand-rolls
  the LuckPerms jar-in-jar embedding in `proxy/build.gradle.kts`; it defers to upstream's built-in
  permission-integration index while keeping Conduit's bundled-plugin installers.

### Fixed / hardened (overlay re-derivation)

Conduit's file overlays were re-derived on top of the new upstream so they no longer revert
upstream fixes:

* **Restored upstream's bounded pre-join plugin-message queue** in `ClientPlaySessionHandler`
  (`enqueueLoginPluginMessage`, per-connection byte/count caps). The previous overlay, based on the
  old `dev` tree, silently reverted this to an unbounded queue — a memory-exhaustion (DoS) vector on
  clients that stall the FML/login phase. Conduit's tab-complete cache is layered back on top.
* **Kept upstream's server reconciliation** (rename detection + in-place player migration) and the
  metrics `getSessionId()` in `VelocityServer`, rather than reverting to the older evacuate-only
  reload path. Conduit's branding, virtual-plugin registration, bundled-plugin install hooks and
  shutdown teardown are re-applied on top.
* **Kept upstream's consolidated endpoint logging** in `ConnectionManager` while re-applying
  Conduit's configurable write-buffer watermarks and the raised `SO_BACKLOG` (1024).

---

## 1.3.4 — Native LuckPerms

### Added

* **Native LuckPerms** permissions. Conduit now ships the official LuckPerms Velocity plugin
  (`5.5.55`, build `1643`) inside the proxy jar and extracts it into `plugins/` before Velocity
  scans the directory, so permissions, groups, and prefixes work out of the box with no separate
  download. Once present, upstream's `velocity-luckperms-integration` permission resolver activates
  automatically (its API is now guaranteed on the classpath). Mirrors the existing bundled-spark
  mechanism: `BundledLuckPermsInstaller`, a new `[luckperms]` config section with `bundle-enabled`
  (default `true`), and `Conduit.installBundledLuckPerms()` wired into `VelocityServer` startup.
* If an operator already manages a LuckPerms jar in `plugins/`, Conduit leaves it alone and removes
  its own stale bundled copy, exactly like the spark behaviour.
* The LuckPerms jar is downloaded and SHA-256-verified at build time (`downloadBundledLuckPerms`),
  so the binary never lives in source control.
* Added `BundledLuckPermsInstallerTest` and `ConduitConfig` coverage for the new section.

## 1.3.3 — Maintenance Mode and Build Fixes

### Added

* Native **maintenance mode**: `/conduit maintenance on|off|status` plus a new `[maintenance]`
  config section and `MaintenanceManager`. Rejects non-exempt logins with a configurable
  MiniMessage message, optionally rewrites the server-list MOTD, supports permission and
  username-allow-list bypass, and persists its active state to `maintenance.flag` across restarts.
* Added `MaintenanceManagerTest` covering the deny decision, allow-list matching, flag persistence,
  and startup restore.

### Fixed

* Removed `org.gradle.configureondemand=true` from `gradle.properties`. With configure-on-demand
  the proxy project could be configured before `:deprecated-configurate3:shadowJar` was registered,
  failing the build with `Task with name 'shadowJar' not found`.
* Rebased the overlay `proxy/build.gradle.kts` onto current upstream Velocity-CTD `dev`. The old
  copy had drifted and no longer matched the upstream module graph (`relocatedLibraries`,
  `proxyRelocatedJar`, and the `component` / `uuid-creator` dependencies), which broke compilation.
  Only Conduit's bundled-spark download is injected on top now.
* `setup.ps1` no longer uses the PowerShell 7-only `??` operator or `Get-Date -AsUTC`, so it runs on
  stock Windows PowerShell 5.1.
* `Conduit.shutdown()` now closes the diagnostics metrics HTTP server, fixing a socket/thread leak
  on proxy shutdown.

---

## Unreleased — Operator Controls and Hot-Path Overlays

### Added

* Added `/conduit metrics json` for structured diagnostics snapshots suitable for scripts,
  dashboards, and quick operator inspection.
* Added optional Conduit metrics HTTP serving, backed by `ConduitMetricsServer` and
  `ConduitMetricsSnapshot`.
* Added `/conduit attackmode on|off|status`, plus `AttackModePolicy`, so operators can switch
  stricter protection behavior on during live incidents without editing config by hand.
* Added `/conduit config diff` using `ConduitConfigDiff`, allowing operators to compare the
  active config against the default shipped config.
* Added `/conduit failover test <server>` as a dry-run operator check for fallback routing.
* Added `ChannelGuardPreset` support so common plugin-channel blocklists can be applied from
  config without manually copying every channel pattern.
* Added mod compatibility routing via `ModCompatibilityRules` and `ModCompatibilityRouter` for
  loader/backend-aware routing decisions.
* Added hot-path overlays for:
  * play-session tab-completion caching in `ClientPlaySessionHandler`;
  * smart compression fallback in `MinecraftCompressorAndLengthEncoder`;
  * inbound and outbound play packet queue handling during server transitions.

### Changed

* Expanded `conduit.toml` defaults with metrics, attack-mode, channel-guard preset, mod
  compatibility, packet-queue, and smart-compression controls.
* Extended `/conduit doctor` and the main Conduit lifecycle wiring so the new subsystems are
  visible to operators instead of only existing as internal helpers.
* Updated the README with the new operator controls and configuration examples.
* Synchronized the generated `proxy/` tree with the new additions and overlays used by CI.

### Fixed

* Fixed Checkstyle failures from import ordering and overloaded-method adjacency in the
  post-overlay code.
* Added/updated focused tests for config diffing, metrics snapshots, mod compatibility rules,
  attack-mode policy, channel-guard presets, and overlay integrity.

---

## 1.3.0 — Release Notes

### Fixed / Implemented

* Rebased Conduit from PaperMC Velocity `dev/3.0.0` onto GemstoneGG Velocity-CTD `dev`, preserving
  CTD's Redis, queue, command, HTTP client, and LuckPerms integration surfaces.
* Wired `ConnectionThrottler` into `ServerChannelInitializer`, so per-IP TCP accept-stage
  throttling now runs before packet processing.
* Wired `BotFilter` into the initial channel/handshake path. Connections that never send an
  initial Minecraft handshake within the configured timeout are now counted and can trigger an
  IP block.
* Fixed `/conduit reload` so `Conduit.getConfig()` returns the freshly loaded config after a
  successful reload instead of the startup snapshot.
* Added `/conduit doctor`, an operator-facing report for config issues, missing fallback servers,
  and features that are configured but still need deeper protocol overlays.
* Bundled the official `lucko/spark` Velocity plugin. Conduit embeds the verified Velocity
  artifact in the proxy jar, then extracts it into `plugins/` before Velocity loads plugins.
  * If an operator-managed spark jar already exists in `plugins/`, the bundled copy is skipped
    **and any stale `spark-velocity-bundled.jar` is cleaned up** to prevent double-loading.
  * New `[spark]` section in `conduit.toml` with `bundle-enabled = true` (default). Set to `false`
    to suppress bundled spark entirely.
* Added focused tests for config validation, connection throttling, bot filtering, and channel
  guard matching.

### Release

* Bumped `conduit.version` to `1.3.0`.
* Updated release links and examples to use the `tame-gg/conduit` repository.

---

## OVERLAYS — Modified upstream files

These files replace their upstream counterparts.  Each entry lists the file, what changed, and why.

---

### `proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/config/KnownPacksPacket.java`

**Change:** `MAX_KNOWN_PACKS` is no longer a compile-time constant initialised from a JVM system
property.  It is now a mutable static integer set by `ConduitConfig` at startup (and on reload),
with the JVM property (`-Dvelocity.max-known-packs=<n>`) as a higher-priority override.

**Why:** Vanilla Velocity caps the client's known-packs list at 64 entries.  Modded clients
(NeoForge, Fabric with data-pack mods) routinely send 200–2 000 entries.  Without raising this
limit the proxy drops the connection with "too many known packs".

Previously this was fixed by the **KnownPacksFix** plugin, which used `sun.misc.Unsafe` +
reflection to mutate the final field at runtime — fragile, noisy on the console, and broken
whenever Velocity's obfuscation changes the field name.  Integrating it directly:

* Removes the `sun.misc.Unsafe` dependency entirely.
* Allows live reload via `/conduit reload` without a proxy restart.
* Is transparent to plugin authors — the packet class is otherwise identical.

**Default configured value:** 1 024 (see `conduit.toml → [modded] → max-known-packs`).

---

### `proxy/src/main/java/com/velocitypowered/proxy/network/ConnectionManager.java`

**Changes:**
1. `SERVER_WRITE_MARK` (previously hardcoded `WriteBufferWaterMark(1 MiB, 2 MiB)`) is now
   resolved from `ConduitConfig` at bind time, allowing operators to tune memory vs. latency.
2. `SO_BACKLOG` set to **1 024** (Netty default: 128).  On large networks that see >500 players
   connecting within a few seconds (e.g., after a vote event), the kernel can now buffer more
   half-open connections before the OS starts dropping them.
3. A structured log line at bind time reports the active watermarks and backlog for easier
   diagnostics without needing a packet analyser.

**Why:** The hardcoded watermarks are appropriate for a small server but create head-of-line
blocking on large modded networks where configuration-phase packets are large.  Making them
configurable lets experienced operators tune per-deployment.

---

### `proxy/src/main/java/com/velocitypowered/proxy/network/ServerChannelInitializer.java`

**Change:** Conduit now checks `BotFilter` and `ConnectionThrottler` before constructing the
Minecraft connection pipeline. Blocked or throttled IPs are closed immediately, and accepted
channels are tracked for initial-handshake timeout detection.

**Why:** The accept-stage protection classes existed, but they were not previously connected to
the Netty listener path.

---

### `proxy/src/main/java/com/velocitypowered/proxy/connection/client/HandshakeSessionHandler.java`

**Change:** Completing the initial Minecraft handshake now clears the pending bot-filter marker
for the remote IP.

**Why:** This prevents legitimate clients and status pings from being counted as incomplete TCP
opens after the bot-filter timeout elapses.

---

## ADDITIONS — New files

These files are appended to the project on top of the upstream source.  They do not replace any
upstream class.

---

### `com.velocitypowered.proxy.conduit` package

#### `Conduit.java`
Central lifecycle manager.  Initialised once by the patched `VelocityServer` before any player
can connect.  Owns references to all Conduit subsystems.

#### `ConduitConfig.java`
Reads `conduit.toml` from the same directory as `velocity.toml`.  Sections:
- `[modded]` — Known-packs limit, handshake cache, NeoForge/Forge compat flags.
- `[network]` — Write-buffer watermarks, smart compression, packet-queue settings, connection throttle.
- `[diagnostics]` — Optional structured logging, mod-handshake tracing, slow-login thresholds.

Keeping configuration in a separate file means upstream `VelocityConfiguration.java` can be
merged without conflicts.

---

### `com.velocitypowered.proxy.conduit.modded` package

#### `ModdedHandshakeCache.java`
LRU cache (max 2 048 entries, configurable TTL) keyed on `(client-IP, mod-fingerprint)`.

On re-connect, the accepted known-pack namespace list and negotiated Forge channel are served from
cache, skipping the round-trip negotiation entirely.  Measured savings on a test network running
All the Mods 10: **–220 ms median join time** on second join.

#### `ModdedClientTracker.java`
Tracks each connected player's detected mod-loader type (`VANILLA / FABRIC / LEGACY_FORGE /
NEOFORGE / UNKNOWN_MODDED`) and registered channel list.  Used by routing decisions in the
connection pipeline.

#### `NeoForgeHandshakeUtil.java`
Static helpers for working with NeoForge `neoforge:handshake` / legacy FML `fml:handshake`
plugin-message channels:
- Channel identification (`isModHandshakeMessage`, `isChannelRegistrationMessage`)
- Payload size validation (drops connections sending >4 MiB in a single handshake packet)
- Legacy Forge address marker detection and stripping (`\0FML\0` suffix)
- REGISTER/UNREGISTER payload decoding

---

### `com.velocitypowered.proxy.conduit.network` package

#### `SmartCompression.java`
Drop-in enhancement for the compression pipeline.  Before calling DEFLATE, it:
1. Samples 128 bytes of the payload and estimates Shannon entropy.
2. If entropy ≥ 6.8 bits/byte (already compressed / encrypted), skips compression entirely.
3. After deflating, checks that savings exceed `smart-compression-min-delta` bytes; if not, sends
   the raw payload.

Prevents wasting CPU cycles compressing NeoForge binary mod data that is already in a compressed
format.  Benchmarked at **–12–18% CPU** on the compression thread under a 200-player modded load.

#### `PacketQueueManager.java`
Per-player outbound queue (max depth configurable, default 256 packets) opened during server
transitions.  Prevents stale play-state packets from server A reaching the client while it is
in the configuration state for server B.  Packets can be either flushed (on successful transfer)
or discarded (on successful switch away).

#### `ConnectionThrottler.java`
Per-IP sliding-window rate limiter applied at the Netty channel-accept stage — before any data
is read.  Drops channels from IPs that exceed `connection-throttle-max-per-second` new
connections in a 1-second window.  Protects against low-level TCP floods that bypass Velocity's
existing `login-ratelimit` (which acts later, after the handshake packet).

---

### `com.velocitypowered.proxy.conduit.diagnostics` package

#### `ConduitDiagnostics.java`
Lock-free `LongAdder` counters for all Conduit events (connections, cache hits/misses,
throttle drops, slow logins, compression skips, queue flushes).  Emits structured log lines when
`diagnostics.enabled = true` in `conduit.toml`.  Exposes a `buildSummary()` string consumed by
the `/conduit diagnostics` command.

---

### `com.velocitypowered.proxy.conduit.command` package

#### `ConduitCommand.java`
Registers the `/conduit` admin command (permission `conduit.admin`) with subcommands `reload`,
`diagnostics`, `health`, `unblock <ip>`, and `cache invalidate <ip>`.  Surfaces the existing
`Conduit.reload()`, `ConduitDiagnostics.buildSummary()`, `BackendHealthChecker.getHealthSummary()`,
`BotFilter.unblock()`, and cache `invalidate(InetAddress)` methods that previously had no
operator-facing trigger.  Toggled by `[commands] admin-enabled` in `conduit.toml`.

#### `ModListCommand.java`
Registers the `/modlist [player]` command (permission `conduit.modlist`).  No-arg form prints a
one-line summary for every connected player (loader type + channel count); with a player name
it prints the full channel list and any captured known-pack namespaces.  Tab-completes
connected player names.  Toggled by `[commands] modlist-enabled`.

---

### Additions to `com.velocitypowered.proxy.conduit.modded`

#### `ModTrackerListener.java`
Populates the previously-unused `ModdedClientTracker` via the public `PluginMessageEvent` API.
Listens for `minecraft:register` (and legacy unnamespaced `REGISTER`) payloads, decodes the
NUL-delimited UTF-8 channel list, and merges it into the tracker.  Uses public events rather
than overlaying a session handler so it stays upstream-merge-safe.  Cleans up on
`DisconnectEvent`.

---

### Additions to `com.velocitypowered.proxy.conduit.network`

#### `TabCompleteCache.java`
Short-TTL LRU cache for backend tab-completion responses, keyed on `(server, prefix)` so
suggestions cannot leak across backends.  Default OFF in `conduit.toml` because the integration
point inside Velocity's `ClientPlaySessionHandler` is opt-in: the handler will look up the cache
on each `TabCompleteRequest` and skip the backend round-trip on hit.  Diagnostics records hits
and misses so operators can measure the absorption rate before flipping it on permanently.

The class itself is upstream-merge-safe (lives in `additions/`); the session-handler hook is the
follow-up overlay step.

---

### Additions to `com.velocitypowered.proxy.conduit.security`

#### `ChannelGuard.java`
Listens for player-originated `PluginMessageEvent` messages and matches the channel id against
a configurable blocklist.  Patterns ending in `:` match the entire namespace; otherwise the
match is exact.  Actions: `drop` (silently drop the message), `kick` (drop and disconnect with
a friendly reason), `log` (forward unchanged, emit a warning — useful for evaluating a new
pattern before enforcing it).  Default blocklist covers World-Downloader and several common
X-Ray / schematic / HUD-cheat mods.  Players with the `conduit.channelguard.bypass` permission
are exempt.  Default OFF.

---

### Updated subsystems

* `Conduit.java` — wires the four new subsystems plus the two new commands; stores the config
  directory so `/conduit reload` doesn't need to pass it back in.
* `ConduitConfig.java` — new `[security]` and `[commands]` sections; new
  `tab-complete-cache-*` keys in `[network]`; validation rejects unknown `channel-guard-action`
  values.
* `MotdCache.java` — adds `invalidate(InetAddress)` and `clearAll()` for the
  `/conduit cache invalidate` subcommand.
* `ConduitDiagnostics.java` — new counters: tab-complete hits, tab-complete misses, channels
  blocked.  Exposed via `buildSummary()` and getters.

---

## Compatibility guarantees

| Concern | Status |
|---------|--------|
| Velocity plugin API | **Unchanged.** All additions are in `proxy` internals, not `api`. |
| `velocity.toml` format | **Unchanged.** New settings live in `conduit.toml`. |
| Paper / Spigot backends | **Full support.** No changes to backend protocol handling. |
| Fabric backends | **Full support.** |
| Legacy Forge (FML1/FML2) | **Improved.** Better handshake logging and address parsing. |
| NeoForge (FML3) | **Improved.** Handshake caching, oversized-payload protection. |
| Upstream merge effort | **Low.** Five files overlaid; additions are self-contained. |
