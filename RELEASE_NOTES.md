# Conduit 1.3.0

Conduit 1.3.0 is the first release that wires the low-level protection classes into Velocity's
actual connection path.

## Highlights

* Per-IP connection throttling now runs in `ServerChannelInitializer` before packet processing.
* Bot filtering now tracks TCP channels that never complete the initial Minecraft handshake.
* `/conduit reload` now refreshes the live `ConduitConfig` snapshot.
* `/conduit doctor` reports configuration issues, missing fallback servers, and not-yet-wired
  experimental settings.
* The official `lucko/spark` Velocity plugin is now bundled and installed automatically as
  `/sparkv` / `/sparkvelocity` unless a spark jar already exists in `plugins/`.
* Added focused tests for Conduit config validation, throttling, bot filtering, and channel guard
  matching.

## Upgrade Notes

* Version string: `1.3.0`.
* Existing `conduit.toml` files continue to work.
* `tab-complete-cache`, `smart-compression`, and `packet-queue-optimization` remain visible in
  config, but `/conduit doctor` now warns that their deeper protocol overlays are not wired yet.
