# Conduit 1.3.4

Conduit 1.3.4 adds **native LuckPerms** — the official LuckPerms Velocity plugin is now bundled
inside the proxy jar and installed automatically on first run, so permissions, groups, and prefixes
work out of the box with no separate download.

## New feature — Native LuckPerms

Permissions normally require operators to download LuckPerms separately and drop it into `plugins/`.
Conduit now ships it natively.

* Conduit bundles the official **LuckPerms Velocity `5.5.55`** (build `1643`) inside the proxy jar
  and extracts it into `plugins/` *before* Velocity scans the directory, so it loads on the same
  boot — exactly like the bundled spark profiler.
* Upstream Velocity-CTD's `velocity-luckperms-integration` permission resolver now lights up
  automatically, because the LuckPerms API is guaranteed to be on the classpath.
* **Operator-managed installs are respected.** If you already keep a LuckPerms jar in `plugins/`,
  Conduit leaves it untouched and cleans up its own stale bundled copy. Don't want it bundled at
  all? Set `bundle-enabled = false` under the new `[luckperms]` section in `conduit.toml`.
* The jar is downloaded and **SHA-256-verified at build time**, so no binary lives in source
  control and a tampered download fails the build.

---

# Conduit 1.3.3

Conduit 1.3.3 adds native **maintenance mode**, fixes several build and runtime bugs, and refreshes
the proxy build overlay so the fork compiles cleanly against the current Velocity-CTD `dev`.

## New feature — Maintenance mode

A capability operators normally add to Velocity with a separate plugin is now built in.

* `/conduit maintenance on|off|status` toggles network-wide maintenance live (permission
  `conduit.admin`). No restart, no config edit required.
* While active, non-exempt logins are rejected with a configurable MiniMessage kick message, and
  the server-list ping can advertise a maintenance MOTD.
* Two independent bypass mechanisms: the `conduit.maintenance.bypass` permission **or** a username
  allow-list (so an owner can get in even before a permissions plugin has loaded).
* State is persisted to `<configDir>/maintenance.flag`, so a crash-restart during maintenance does
  not silently reopen the network. Configure defaults under `[maintenance]` in `conduit.toml`.

## Bug fixes

* **Build: `configure-on-demand` broke the build.** `org.gradle.configureondemand=true` caused the
  proxy project to be configured before `:deprecated-configurate3:shadowJar` was registered, failing
  the build with `Task with name 'shadowJar' not found`. The flag has been removed.
* **Build: overlay `proxy/build.gradle.kts` had drifted** from upstream and no longer matched the
  current module graph (missing `relocatedLibraries`/`proxyRelocatedJar` and the `component` /
  `uuid-creator` dependencies). It has been rebased onto current upstream with only Conduit's
  bundled-spark download injected.
* **Windows `setup.ps1` failed on Windows PowerShell 5.1.** It used the PowerShell 7-only `??`
  operator and `Get-Date -AsUTC`; both are now 5.1-compatible.
* **Metrics endpoint leaked on shutdown.** `Conduit.shutdown()` now closes the diagnostics HTTP
  server instead of leaving its socket/thread dangling.

## Upgrade Notes

* Version string: `1.3.3`.
* Existing `conduit.toml` files continue to work; the new `[maintenance]` section is optional and
  defaults to an enabled-but-inactive maintenance subsystem.
* If you build on Windows with Windows PowerShell 5.1, `setup.ps1` now runs without manual edits.
