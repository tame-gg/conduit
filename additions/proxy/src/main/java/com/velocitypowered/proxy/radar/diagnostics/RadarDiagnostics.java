/*
 * Conduit — a performance-focused fork of Velocity for modded networks.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.velocitypowered.proxy.radar.diagnostics;

import com.velocitypowered.proxy.radar.RadarConfig;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Collects runtime metrics and emits diagnostic log messages for Conduit.
 *
 * <p>Metrics are kept in lock-free {@link LongAdder} counters so recording them on hot paths has
 * negligible overhead even when diagnostics are disabled (the {@code enabled} flag short-circuits
 * before any string formatting).
 *
 * <p>Planned integration: a future {@code /radarvelocity diagnostics} command will print a
 * formatted summary of all counters and the slowest-login histogram.
 */
public final class RadarDiagnostics {

  private static final Logger logger = LogManager.getLogger(RadarDiagnostics.class);

  private volatile boolean enabled;
  private volatile boolean traceModHandshakes;
  private volatile int slowThresholdMs;

  // ── Counters ───────────────────────────────────────────────────────────────
  private final LongAdder totalConnections       = new LongAdder();
  private final LongAdder modddedConnections     = new LongAdder();
  private final LongAdder handshakeCacheHits     = new LongAdder();
  private final LongAdder handshakeCacheMisses   = new LongAdder();
  private final LongAdder throttledConnections   = new LongAdder();
  private final LongAdder oversizedPayloads      = new LongAdder();
  private final LongAdder slowLogins             = new LongAdder();
  private final LongAdder compressionSkips       = new LongAdder();
  private final LongAdder packetQueueFlushes     = new LongAdder();

  /** Per-player login timing (player name → connect-start millis). */
  private final ConcurrentHashMap<String, Long> loginTimings = new ConcurrentHashMap<>();

  public RadarDiagnostics(RadarConfig config) {
    reconfigure(config);
  }

  public void reconfigure(RadarConfig config) {
    this.enabled = config.isDiagnosticsEnabled();
    this.traceModHandshakes = config.isTraceModHandshakes();
    this.slowThresholdMs = config.getSlowConnectionThresholdMs();
  }

  // ── Event recording ────────────────────────────────────────────────────────

  public void recordConnection(String playerName) {
    totalConnections.increment();
    if (enabled) loginTimings.put(playerName, System.currentTimeMillis());
  }

  public void recordModdedConnection(String playerName, String modType) {
    modddedConnections.increment();
    if (enabled) {
      logger.info("[Conduit] Modded connection: {} ({})", playerName, modType);
    }
  }

  public void recordLoginComplete(String playerName) {
    if (!enabled) return;
    Long start = loginTimings.remove(playerName);
    if (start == null) return;
    long elapsed = System.currentTimeMillis() - start;
    if (elapsed > slowThresholdMs) {
      slowLogins.increment();
      logger.warn("[Conduit] Slow login: {} took {}ms (threshold {}ms)",
          playerName, elapsed, slowThresholdMs);
    } else {
      logger.debug("[Conduit] Login complete: {} in {}ms", playerName, elapsed);
    }
  }

  public void recordHandshakeCacheHit(String playerName) {
    handshakeCacheHits.increment();
    if (traceModHandshakes) {
      logger.info("[Conduit] Handshake cache HIT for {}", playerName);
    }
  }

  public void recordHandshakeCacheMiss(String playerName) {
    handshakeCacheMisses.increment();
    if (traceModHandshakes) {
      logger.info("[Conduit] Handshake cache MISS for {}", playerName);
    }
  }

  public void recordThrottledConnection(String address) {
    throttledConnections.increment();
  }

  public void recordOversizedPayload(String playerName, String channel, int bytes) {
    oversizedPayloads.increment();
  }

  public void recordCompressionSkip() {
    compressionSkips.increment();
  }

  public void recordPacketQueueFlush(String playerName, int packetCount) {
    packetQueueFlushes.increment();
    if (enabled) {
      logger.debug("[Conduit] Packet queue flushed {} packets for {}", packetCount, playerName);
    }
  }

  public void traceModPacket(String playerName, String channel, String direction, int bytes) {
    if (traceModHandshakes) {
      logger.info("[Conduit] MOD-TRACE {} {} ch='{}' {} bytes",
          direction, playerName, channel, bytes);
    }
  }

  // ── Snapshot ──────────────────────────────────────────────────────────────

  public String buildSummary() {
    return String.format(
        "Conduit Diagnostics Snapshot%n"
        + "  Total connections      : %d%n"
        + "  Modded connections     : %d%n"
        + "  Handshake cache hits   : %d%n"
        + "  Handshake cache misses : %d%n"
        + "  Throttled connections  : %d%n"
        + "  Oversized payloads     : %d%n"
        + "  Slow logins (>%dms)    : %d%n"
        + "  Compression skips      : %d%n"
        + "  Packet queue flushes   : %d%n",
        totalConnections.sum(),
        modddedConnections.sum(),
        handshakeCacheHits.sum(),
        handshakeCacheMisses.sum(),
        throttledConnections.sum(),
        oversizedPayloads.sum(),
        slowThresholdMs,
        slowLogins.sum(),
        compressionSkips.sum(),
        packetQueueFlushes.sum());
  }

  // ── Getters for tests ─────────────────────────────────────────────────────
  public long getTotalConnections()     { return totalConnections.sum(); }
  public long getModdedConnections()    { return modddedConnections.sum(); }
  public long getHandshakeCacheHits()   { return handshakeCacheHits.sum(); }
  public long getHandshakeCacheMisses() { return handshakeCacheMisses.sum(); }
  public long getThrottledConnections() { return throttledConnections.sum(); }
  public long getSlowLogins()           { return slowLogins.sum(); }
  public long getCompressionSkips()     { return compressionSkips.sum(); }
}
