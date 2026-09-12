# Conduit 1.8.0

## Fixed

- **The tab-complete cache could show one player another player's command suggestions.** Completions
  are filtered for whoever asked for them, but cached entries were keyed only on the server and the
  typed text — so an administrator typing `/` filled the entry that the next player typing `/` was
  served. Entries are now per player, and are dropped when that player disconnects. Only networks
  that had turned on the opt-in `tab-complete-cache` were affected.
- **The bot filter now actually counts a flood.** It tracked one pending handshake per IP address
  rather than per connection, so a source opening connections in quick succession — the shape of
  every real flood — kept resetting that single slot and almost nothing was counted. Handshakes are
  tracked per connection now. Ordinary server-list pings still never count against anyone.
- **A single missed health-check ping no longer pulls a backend out of routing.** One long GC pause
  on a backend was enough to mark it unhealthy and then healthy again moments later. A backend now
  goes down after 3 consecutive failed pings and comes back after 2 consecutive good ones; both are
  configurable (`health-check-failure-threshold`, `health-check-success-threshold`).
- **A connection flood no longer floods your log.** The connection throttle logged a line per
  dropped connection, on the accept path. Drops are now summarised per source at most once every
  5 seconds (`connection-throttle-log-interval-ms`).
- **The metrics endpoint can no longer be wedged by one idle client.** A connection that opened and
  sent nothing used to block the endpoint for everyone, scrapers included. Requests are handled on
  a worker pool with read timeouts.
- **The MOTD cache no longer mixes up clients.** Cached pings were keyed on the address alone,
  ignoring the client's version and the hostname it connected to, so two players behind one
  connection — or one player pinging two of your hostnames — could receive each other's answer.

- **`fallback-servers` now does what it says.** The router was checking the wrong half of
  Velocity's kick event: it acted only when a player's *move to another server* failed — a case
  where they are still safely on their current server and Velocity already handles it correctly —
  and did nothing when a player actually lost their server or when their first connection on login
  could not be reached. Those are exactly the cases the list is configured for, and they work now.
  Velocity's own `try` list still goes first; Conduit steps in when it is exhausted or when the
  server it picked is one the health checker knows is down.

## Added

- **IPv6 floods are counted properly.** The throttle and bot filter used to count each address
  separately, which an attacker holding a single IPv6 `/64` — 18 quintillion addresses — escaped
  for free. Sources are now grouped by network prefix, `/64` for IPv6 and `/32` for IPv4 by
  default, and both are configurable.
- **Prometheus support on the metrics endpoint.** `/metrics/prometheus` serves the same counters in
  the Prometheus text format, so you can point a scraper straight at Conduit. Set
  `metrics.auth-token` to require a bearer token, which is what makes binding it off loopback
  reasonable. `/conduit metrics prometheus` prints the same thing in console.
- **Rolling restarts without kicking anyone.** `/conduit drain <server>` closes a backend to new
  players and moves the players on it somewhere routable; `/conduit undrain <server>` reopens it. A
  draining server still reports as healthy — it is a routing decision, not a health problem. Grant
  your staff `conduit.drain.bypass` and they can still walk onto a draining server, and are never
  moved off it, so you can watch the restart from inside while everyone else is kept away.
- **Tighter control over backend → proxy command forwarding.** A forwarded console command runs
  with your proxy console's full authority, which is a lot of trust to place in every backend on
  the network. `allowed-servers` limits which backends may forward, and `command-allowlist` /
  `command-denylist` limit what they may run. Defaults are unchanged (empty = as before).

## Changed

- **`/conduit reload` now applies far more, and tells you the truth about the rest.** Turning a
  subsystem on or off in `conduit.toml` — the bot filter, channel guard, MOTD cache, tab-complete
  cache, health checks, connection throttle, or command forwarding — used to require a restart, and
  reloading appeared to succeed while quietly doing nothing. All of those now take effect on
  reload, along with their thresholds, TTLs, block-lists, and fallback order. The reload then names
  the specific keys you changed that genuinely need a restart, instead of printing a standing list
  of caveats.

## Build

- Refreshed the bundled LuckPerms (5.5.84) and spark (1.10.185) jars after upstream rotated both
  download URLs.

---

# Conduit 1.7.5

## Fixed

- **Creative-mode reach no longer follows you into a survival server.** Logging in on a server
  where you are in creative and then switching seamlessly to a server where you are in survival
  kept the extended block and entity reach, because since 1.20.5 that reach is an attribute
  modifier the client holds on to until a server replaces the attribute — and a survival server has
  no reason to send one. The proxy now remembers the attributes a backend gives you and replays
  them without their modifiers as part of a seamless switch, so your reach matches the game mode of
  the server you land on. Previously this only sorted itself out by switching servers again.

---

# Conduit 1.7.4

## Added

- **Pin which Minecraft versions your network accepts, and advertise them.** A new `[versions]`
  section in `conduit.toml` lets you name the versions players may join on. Off by default —
  nothing changes until you set `enabled = true`.

  Describe the accepted set either as a contiguous range or as a list of individual versions:

  ```toml
  [versions]
  enabled = true

  # Exact versions, gaps allowed. When non-empty this is the whole accepted set
  # and minimum/maximum are ignored.
  allow = ["1.21", "1.21.1", "1.21.11"]

  # Range mode — used only when `allow` is empty. Equal bounds pin one version;
  # a blank bound means "no limit on that end".
  minimum = "1.21.11"
  maximum = "1.21.11"
  ```

  Either form takes version names (`"1.21.11"`) or raw protocol numbers (`"774"`), as strings or
  bare integers — the protocol form lets you pin a version this build does not name yet.

- **Accepted players see nothing new.** Their server list entry keeps your normal MOTD, the ping
  bars, and the live player count.

- **Everyone else sees which versions you run.** A client outside the accepted set gets the vanilla
  "incompatible version" entry — the red cross in place of the ping bars — labelled `Conduit
  1.21.11` (configurable via `ping-version-name`). It says what the network runs rather than
  implying the network is down.

- **A clear message when they try to join.** The connection is refused before authentication with
  "This network only allows players to join on version 1.21.11." when one version is pinned, or the
  plural form naming the range or list otherwise. Both templates are configurable through
  `kick-message` and `kick-message-range`, with `{versions}`, `{min}`, and `{max}` placeholders.

- The range is live-reloadable with `/conduit reload` and shows up in `/conduit config diff`.
  Existing installs pick the new section up automatically on first start, with values preserved as
  always.

## Notes

- Some Minecraft releases share one protocol number — 1.21 and 1.21.1, 1.21.9 and 1.21.10, all the
  1.8.x releases, and others. The client only sends the number, so no proxy can tell those apart.
  Conduit is honest about it: pinning `1.8` displays and reads back as `1.8–1.8.9`.
- Unknown version names and inverted ranges are refused at startup with an explanatory error,
  rather than silently locking everyone out.
- Versions the proxy itself cannot speak are left to Velocity's own handling, so this setting only
  ever narrows the supported set — it can never widen it.

---

# Conduit 1.7.3

## Fixed

- **Arrows, potion particles, and other leftover visuals no longer follow players across a seamless
  switch.** A seamless switch keeps the client's player entity alive, so every visual stored on that
  entity was inherited from the server the player left, and a destination server only ever sends the
  entity data that differs from its own idea of a freshly joined player — it never clears any of it.
  A switch now resets the player's entity completely: arrows and bee stingers stuck in them, potion
  effect particles and the ambient-effect flag, being on fire, air bubbles, the powder-snow freeze
  overlay, invisibility and glowing, sneaking, sprinting, swimming and elytra flight, the item-use
  and riptide animations, and a crawling, swimming, or sleeping pose. On clients before 1.21.2 any
  leftover momentum is dropped as well; from 1.21.2 the destination's own spawn teleport already
  does that.

---

# Conduit 1.7.2

## Fixed

- **Rain no longer follows players across a seamless switch.** A storm on the server you left kept
  raining on a clear destination (for example the hub). Ending the rain is not enough on its own: the
  client keeps its rain and thunder gradients separate from the "is it raining" flag and expects the
  server to fade them out, and a destination that has clear weather never sends any weather update at
  all. A seamless switch now zeroes both gradients as well, and a destination that *is* raining still
  overrides that with its own weather right after the switch.

- **Status effects no longer stick to players across a seamless switch.** Effects were only removed
  if the proxy had seen them being applied, so anything applied outside that window stayed on the
  HUD forever — and because the destination server never granted it, nothing there could clear it
  either. A seamless switch now also removes every vanilla status effect from the client, on top of
  the ones it tracked, so no phantom effect can survive the switch. Effects the destination applies
  are sent after the cleanup and are unaffected.

- **Dimension changes are detected correctly on 1.20.2–1.20.4.** Those versions send the dimension
  as a registry identifier and leave the numeric dimension field at zero, which made every switch
  look like a same-dimension move. Cross-dimension switches were therefore treated as seamless and
  the client kept the previous world's sky, lighting, and weather. The check now compares whichever
  field actually carries the dimension for the client's protocol.

---

# Conduit 1.7.1

## Fixed

- **Seamless switches no longer strand players who move too early.** Going back to the hub (or any
  seamless switch) briefly held the player "stuck in place until you reconnect" if you moved before
  the destination server finished streaming you in — the movement raced ahead of the not-yet-loaded
  world. The switch now applies a short **settle delay** during which the player's own input is held
  and buffered while the destination loads them in, then released. This removes the desync and, as a
  bonus, stops the switch from feeling jarringly instantaneous.

- **Copyright line updates on existing installs.** Velocity copies `messages.properties` into an
  on-disk `lang/` folder and only ever *adds* missing keys, so the `/velocity` copyright stayed frozen
  at the old `Copyright 2018-2026 …` even after the jar was updated. Conduit now force-refreshes that
  one branding line from the shipped default, so existing installs show `Copyright 2026 tame.gg`.
- **Old `conduit.toml` files pick up the condensed `[advanced]` comments.** The config migrator now
  re-syncs the comment wording above the seamless-switch options to the shipped (shortened) text,
  while preserving every value exactly. No other key is touched.
- **F3 server brand now updates on a seamless switch.** The backend announces its server brand
  (shown on the F3 debug screen, e.g. `"<server>" (Conduit)`) during the configuration phase. A
  seamless switch absorbs that phase, so the new brand never reached the client and F3 kept showing
  the previous server's name. The absorbed brand message is now rewritten and forwarded to the
  client, so F3 reflects the server you are actually on.
- **Stale title/subtitle no longer sticks across a seamless switch.** A title banner set by the
  previous server (e.g. a hub's `"<server>" server` banner) stayed on screen after a same-dimension
  seamless switch, so it looked like every server had the same name. The title/subtitle/action bar
  is now reset as part of the seamless HUD cleanup, matching the non-seamless switch path.
- **Seamless-switch teleport sound now actually plays.** The cue was emitted against the player's
  previous server entity (the connected-server reassignment happens after the switch packets are
  built), so the client heard nothing. It is now sent directly against the player's own client-side
  entity id, so the ender pearl teleport sound plays on every seamless switch.

## Added

- **Configurable seamless-switch settle delay.** New `conduit.toml` →
  `[advanced] seamless-switch-settle-ms` (default **250**, range **0–5000**). Set to `0` to restore
  the previous instantaneous behaviour. This directly softens the "too quick" seamless switch.
- **Ender pearl teleport sound on server switch.** New `conduit.toml` →
  `[advanced] seamless-switch-sound-enabled` (default **true**), `seamless-switch-sound` (default
  `minecraft:entity.enderman.teleport` — the ender pearl teleport sound), `seamless-switch-sound-volume`,
  and `seamless-switch-sound-pitch`. Plays a short cue to the player whenever they are seamlessly
  moved between servers. All of these settings are live-reloadable.

---

# Conduit 1.7.0

## Added

- **Experimental seamless server switches** for Minecraft 1.20.2+ clients, controlled by
  `conduit.toml` → `[advanced] seamless-server-switches` (default **false**). When enabled, eligible
  same-dimension switches between homogeneous backends skip the client configuration screen and keep
  the world loaded. Cross-dimension switches, pre-1.20.2 clients, and Legacy Forge still use the
  existing switch path.

## Fixed

- **Seamless switches now clear leftover scoreboards.** A destination that recreates a common
  objective such as `sidebar` no longer crashes 1.20.2+ clients with
  `An objective with the name 'sidebar' already exists!`. The proxy tracks backend scoreboard
  objectives and teams and removes them from the client before the new server's packets arrive.

- **Seamless switches now apply destination player state.** Hub leftover **boss bars** (TAB RAM/TPS
  bars, including bars that only receive later UPDATE packets), **gamemode**, **operator permission
  level** (F3+F4), and **status effects** are cleared or rewritten from the destination. Packets that
  target the local player (damage, hurt animation, entity sounds, knockback, metadata) are rewritten
  to the client's original entity ID so hurt sounds play. The vanilla world-generation overlay is
  not forwarded after a seamless switch. Leftover boss bars are tracked through Join Game replay and
  removed again after a short delay; air/swim metadata is reset. Seamless joins no longer stall on
  Velocity `ServerConnectedEvent` (that wait dropped movement for about a second). Destination world
  packets follow HUD clear by 300ms, and 1.21.4+ backends are sent `player_loaded` so they accept
  input immediately. Cross-dimension switches (such as Overworld → End limbo) still send Join Game
  and Respawn, but leftover boss bars are stripped first because the client stayed in Play.

### Credits

- **Seamless server switching:** Based on the seamless server switching patch by [@ohemilyy](https://github.com/ohemilyy), originally provided in `b5a97c65eea43a1a3d5e21589b67e2888729e1e4` (`feat: seamless server switches`).

# Conduit 1.6.3

## Fixed

- **LuckPerms now autocompletes Conduit permissions (including maintenance bypass).** Velocity has
  no permission registry, so nodes that Conduit only checks rarely — especially
  `conduit.maintenance.bypass`, which is evaluated only while maintenance mode is active — never
  appeared in `/lpv` tab-complete or the LuckPerms web editor. Conduit now seeds its known
  `conduit.*` nodes into LuckPerms' suggestion tree at startup when LuckPerms is present
  (`conduit.admin`, `conduit.modlist`, `conduit.maintenance.bypass`, `conduit.channelguard.bypass`,
  `conduit.update.notify`, `conduit.forward.execute`).

## Fixed (build)

- **Refreshed the bundled LuckPerms Velocity jar to `5.5.71`.** The LuckPerms download server prunes
  old build numbers, so the previously pinned build `1643` (`5.5.55`) started returning HTTP 404. That
  broke the `:velocity-proxy:downloadBundledLuckPerms` task and therefore the entire build and CI. The
  pinned URL and SHA-256 now point at the current build `1658` (`5.5.71`), sourced from the LuckPerms
  metadata API.

> **Note:** because LuckPerms only retains recent builds, a pinned build can disappear again in the
> future. If `downloadBundledLuckPerms` fails with a 404, refresh `conduit.luckperms.velocity.url` /
> `.sha256` (and `.version`) in `gradle.properties` from `downloads.velocity` at
> <https://metadata.luckperms.net/data/all>.

# Conduit 1.6.2

## Fixed (security)

- **Restored the command-forwarding permission gate.** 1.6.1-hotfix1 removed the
  `conduit.forward.execute` check, which meant any player who could invoke the backend `/proxyexec`
  could run proxy commands (e.g. `/sparkv`) even with no permissions. The gate is back: with
  `[forwarding] require-permission = true`, a forwarded player command runs only if the player holds
  `conduit.forward.execute`. Forgery is still prevented (the message must come from a genuine backend
  connection), and console-originated commands are always allowed.

## Changed

- When a forwarded command is blocked, the log now spells out exactly how to authorise the player —
  e.g. `/lp user <name> permission set conduit.forward.execute true` — because Velocity has no
  permission registry, so the node does not autocomplete in the LuckPerms web editor. It must be
  granted explicitly (to a user or a staff group).
- Supersedes 1.6.1 and 1.6.1-hotfix1; version `1.6.2` sorts above both, so the update checker no
  longer reports a false "behind".

> **Known issue (upstream):** the `libdeflate` base bundles **Adventure 5**, so plugins compiled
> against Adventure 4 (e.g. LibertyBans 1.1.4) fail to load with
> `NoSuchMethodError: TextComponent.ofChildren`. This predates 1.6.x and is tracked separately.

# Conduit 1.6.1-hotfix1

## Fixed

- **Forwarded player commands are no longer blocked by an ungrantable permission.** With
  `[forwarding] require-permission = true`, Conduit gated forwarded player commands on a synthetic
  `conduit.forward.execute` node. Because Velocity has no permission registry, that node never
  appeared in the LuckPerms web editor and could not be granted there, so legitimate players were
  blocked with `Blocked forwarded command from … — missing permission 'conduit.forward.execute'`.

  Forwarded player commands now run **as the player**, so the command manager authorises them against
  each command's **own** permission — a real, editor-visible node — exactly like normal command
  execution. Forgery is still prevented: the message must originate from a genuine backend
  `ServerConnection`, never a client.

## Changed

- `[forwarding] require-permission` is deprecated and no longer enforced (kept so existing
  `conduit.toml` files still parse; a one-line note is logged if it is set true). `EXECUTE_PERMISSION`
  / `conduit.forward.execute` is retained only for source compatibility.

This hotfix supersedes 1.6.1 and includes all of its fixes below.

# Conduit 1.6.1

## Fixed

- **Reported version now matches the release.** The internal version, jar manifest
  (`Implementation-Version` / `Conduit-Version`), and update checker all read a single build-time
  value, so a `conduit-1.6.1.jar` reports `1.6.1` everywhere. Previously the version metadata was
  written only by the setup script and could go stale (a 1.6.0 jar still reported 1.5.2), which made
  the update checker wrongly claim you were behind.
- **Upgrades never reset your configuration — including LuckPerms.** The `conduit.toml` migrator is
  now strictly **append-only** and edits the file text in place: existing sections, keys, values,
  comments, ordering, and blank lines are preserved byte-for-byte, and only genuinely missing options
  are inserted. Previously the migrator round-tripped the whole file through a TOML writer, which
  reordered/reformatted it and made an operator-set value (e.g. `[luckperms] bundle-enabled = false`)
  look like it had been reset.
- Fixed the migrator failing to detect keys in `conduit.toml` files with Windows (CRLF) line
  endings; line-ending style is now preserved on write.

## Changed

- `conduit-build.properties` (version, build time, git hash) is generated by the Gradle build from
  `conduit.version`, making version reporting authoritative and automatic.
- The manifest now carries the real Conduit version instead of the upstream `-SNAPSHOT` coordinate.
- Rewrote `ConduitConfigMigrator` as an append-only, formatting-preserving text migrator:
  - New sections are appended verbatim from the shipped defaults (header + comments + defaults).
  - New keys are inserted under their existing section header, copied from the shipped defaults.
  - Renamed options carry the operator's value to the new key (via the `RENAMES` table); removed
    options are simply ignored by the loader — no manual cleanup, no reset.
  - Added regression tests for verbatim preservation, LuckPerms/Spark value retention, and CRLF files.

## Migration behaviour

Dropping in a newer Conduit jar now migrates `conduit.toml` automatically on startup, regardless of
the version upgraded from: new options appear with documented defaults, existing settings are left
exactly as written, and users never need to delete or recreate the file.

# Conduit 1.6.0

## Fixed

- **Client-side command synchronization / tab-completion is fixed.** Since 1.4.0, commands sent
  through the proxy rendered red ("unknown"), would not autofill, and produced no Brigadier
  suggestions — even though they still executed on the backend. The command tree is now serialized
  and applied by the client correctly again.
- Fixed a secondary latent defect where a *stale* Brigadier command tree could be flushed to the
  client after a server switch; queued command trees are now collapsed to the newest one.

## Root cause (the tab-completion regression)

Conduit overlays `proxy/build.gradle.kts`, and that overlay carried a shadow-jar exclude,
`exclude("it/unimi/dsi/fastutil/objects/*ObjectArray*")`, that upstream Velocity-CTD removed in
commit `082dd9fb` ("fix: fix tab completion", #1028). When the 1.4.0 rebrand re-synced Conduit onto a
newer upstream base, that base began using fastutil's `ObjectArrayList` in the command-graph /
tab-completion path — but Conduit's overlay was still stripping `ObjectArrayList` out of the release
jar. The `AvailableCommandsPacket` therefore failed to encode (a `NoClassDefFoundError` deep in the
packet encoder, with no error surfaced in the console), so the client silently received an unusable
command tree. Version 1.3.5 was unaffected because its older upstream base did not yet reference that
class, which is why the break appeared to start at 1.4.0.

The exclude has been removed from the overlay to match upstream #1028; `ObjectArrayList` is now
present in the jar and the command tree encodes correctly.

## Changed

- Removed the stale `*ObjectArray*` shadow-jar exclude from the `proxy/build.gradle.kts` overlay.
- Rebuilt the CONFIG-state outbound packet queue's command-tree handling: queued
  `AvailableCommandsPacket`s are collapsed to the single most recent tree, so FIFO flush order can
  never surface a stale tree, and command trees no longer count against the queue depth cap. Covered
  by a new regression test (`collapsesQueuedCommandTreesToTheNewest`).
- The release artifact is now named `conduit-<version>.jar` instead of the upstream
  `velocity-proxy-<...>-all.jar` coordinate.

## Verified

- Command autofill / tab-completion confirmed working again on Minecraft 26.2 through the proxy.
- Bundled Spark, native LuckPerms (plus the LuckPerms permission-integration resolver), and the
  optional VelocityCommandForward integration remain embedded in the jar.
- `max-known-packs` large-modpack support wired end-to-end (default 1024, `conduit.toml`
  configurable, `-Dvelocity.max-known-packs` JVM override precedence) — no 64-entry cap remains.
- Full Conduit test suite green on JDK 25.

# Conduit 1.5.2

## Fixed

- Fixed CI formatting failure by adding the required project license header to the command-tree queue regression test.

## Technical

- No production behavior changed from 1.5.1; this patch release makes the verified regression test pass repository formatting checks.

# Conduit 1.5.1

## Fixed

- Fixed command synchronization through the proxy causing valid backend commands to appear red or unknown in the client.
- Preserved the `AvailableCommandsPacket` Brigadier tree while the client connection transitions from CONFIG to PLAY.
- Kept command execution, permissions, aliases, forwarding, and tab completion behavior unchanged.

## Changed

- The bounded packet-queue optimization now evicts ordinary packets before evicting a command tree.
- Added a regression test covering command-tree preservation under queue pressure.

## Technical

Conduit’s queue optimization previously dropped the oldest queued PLAY packet whenever the queue
reached its cap. `AvailableCommandsPacket` is a PLAY packet, so a busy server switch could evict
the complete serialized Brigadier tree before it was flushed to the client. The client then had no
matching command nodes and rendered commands red, even though the backend dispatcher could execute
them. The outbound queue now identifies `AvailableCommandsPacket` and evicts the oldest ordinary
packet instead; redundant command-tree updates are dropped only when no ordinary packet is
available. The change is confined to `PlayPacketQueueOutboundHandler`.
