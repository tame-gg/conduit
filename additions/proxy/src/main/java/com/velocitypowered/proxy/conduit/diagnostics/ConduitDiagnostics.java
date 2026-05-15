/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.conduit.diagnostics;

import com.velocitypowered.proxy.conduit.ConduitConfig;
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
public final class ConduitDiagnostics {

  private static final Logger logger = LogManager.getLogger(ConduitDiagnostics.class);

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

  public ConduitDiagnostics(ConduitConfig config) {
    reconfigure(config);
  }

  /** Updates configuration fields from a freshly loaded {@link ConduitConfig}. */
  public void reconfigure(ConduitConfig config) {
    this.enabled = config.isDiagnosticsEnabled();
    this.traceModHandshakes = config.isTraceModHandshakes();
    this.slowThresholdMs = config.getSlowConnectionThresholdMs();
  }

  // ── Event recording ────────────────────────────────────────────────────────

  /** Records a new connection attempt from the named player. */
  public void recordConnection(String playerName) {
    totalConnections.increment();
    if (enabled) {
      loginTimings.put(playerName, System.currentTimeMillis());
    }
  }

  /** Records that the named player connected using the given mod type. */
  public void recordModdedConnection(String playerName, String modType) {
    modddedConnections.increment();
    if (enabled) {
      logger.info("[Conduit] Modded connection: {} ({})", playerName, modType);
    }
  }

  /** Records that the named player completed login, and logs slow-login warnings. */
  public void recordLoginComplete(String playerName) {
    if (!enabled) {
      return;
    }
    Long start = loginTimings.remove(playerName);
    if (start == null) {
      return;
    }
    long elapsed = System.currentTimeMillis() - start;
    if (elapsed > slowThresholdMs) {
      slowLogins.increment();
      logger.warn("[Conduit] Slow login: {} took {}ms (threshold {}ms)",
          playerName, elapsed, slowThresholdMs);
    } else {
      logger.debug("[Conduit] Login complete: {} in {}ms", playerName, elapsed);
    }
  }

  /** Records a handshake-cache hit for the named player. */
  public void recordHandshakeCacheHit(String playerName) {
    handshakeCacheHits.increment();
    if (traceModHandshakes) {
      logger.info("[Conduit] Handshake cache HIT for {}", playerName);
    }
  }

  /** Records a handshake-cache miss for the named player. */
  public void recordHandshakeCacheMiss(String playerName) {
    handshakeCacheMisses.increment();
    if (traceModHandshakes) {
      logger.info("[Conduit] Handshake cache MISS for {}", playerName);
    }
  }

  /** Records a connection that was dropped by the throttler. */
  public void recordThrottledConnection(String address) {
    throttledConnections.increment();
  }

  /** Records an oversized plugin-message payload from the named player on the given channel. */
  public void recordOversizedPayload(String playerName, String channel, int bytes) {
    oversizedPayloads.increment();
  }

  /** Records that smart compression chose to skip compressing a packet. */
  public void recordCompressionSkip() {
    compressionSkips.increment();
  }

  /** Records that the packet queue was flushed for the named player. */
  public void recordPacketQueueFlush(String playerName, int packetCount) {
    packetQueueFlushes.increment();
    if (enabled) {
      logger.debug("[Conduit] Packet queue flushed {} packets for {}", packetCount, playerName);
    }
  }

  /** Traces a mod plugin-message packet for diagnostic purposes. */
  public void traceModPacket(String playerName, String channel, String direction, int bytes) {
    if (traceModHandshakes) {
      logger.info("[Conduit] MOD-TRACE {} {} ch='{}' {} bytes",
          direction, playerName, channel, bytes);
    }
  }

  // ── Snapshot ──────────────────────────────────────────────────────────────

  /** Returns a formatted multi-line string with a snapshot of all diagnostic counters. */
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

  /** Returns the total number of connections recorded. */
  public long getTotalConnections() {
    return totalConnections.sum();
  }

  /** Returns the total number of modded connections recorded. */
  public long getModdedConnections() {
    return modddedConnections.sum();
  }

  /** Returns the total number of handshake-cache hits. */
  public long getHandshakeCacheHits() {
    return handshakeCacheHits.sum();
  }

  /** Returns the total number of handshake-cache misses. */
  public long getHandshakeCacheMisses() {
    return handshakeCacheMisses.sum();
  }

  /** Returns the total number of throttled connections. */
  public long getThrottledConnections() {
    return throttledConnections.sum();
  }

  /** Returns the total number of slow-login events. */
  public long getSlowLogins() {
    return slowLogins.sum();
  }

  /** Returns the total number of times compression was skipped. */
  public long getCompressionSkips() {
    return compressionSkips.sum();
  }
}
