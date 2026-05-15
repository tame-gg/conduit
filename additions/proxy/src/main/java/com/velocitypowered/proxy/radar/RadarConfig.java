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

package com.velocitypowered.proxy.radar;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads and exposes all Conduit-specific settings from {@code radar.toml}.
 *
 * <p>Keeping this in a separate file (rather than patching velocity.toml) means upstream
 * VelocityConfiguration can be merged without conflicts.
 */
public final class RadarConfig {

  private static final Logger logger = LogManager.getLogger(RadarConfig.class);
  public static final int DEFAULT_MAX_KNOWN_PACKS = 1024;

  // ── Modded section ────────────────────────────────────────────────────────
  private final int maxKnownPacks;
  private final boolean handshakeCacheEnabled;
  private final int handshakeCacheTtlSeconds;
  private final int moddedHandshakeTimeoutMs;
  private final boolean neoforgeCompatMode;
  private final boolean legacyForgeCompatMode;
  private final boolean announceModdedInPing;
  private final boolean logModHandshakes;

  // ── Network section ───────────────────────────────────────────────────────
  private final int writeBufferHighWatermark;
  private final int writeBufferLowWatermark;
  private final boolean smartCompressionEnabled;
  private final int smartCompressionMinSizeDelta;
  private final boolean packetQueueOptEnabled;
  private final int packetQueueMaxDepth;
  private final int connectionThrottleMaxPerSecond;
  private final boolean connectionThrottleEnabled;

  // ── Diagnostics section ───────────────────────────────────────────────────
  private final boolean diagnosticsEnabled;
  private final boolean traceModHandshakes;
  private final int slowConnectionThresholdMs;

  private RadarConfig(Builder b) {
    this.maxKnownPacks = b.maxKnownPacks;
    this.handshakeCacheEnabled = b.handshakeCacheEnabled;
    this.handshakeCacheTtlSeconds = b.handshakeCacheTtlSeconds;
    this.moddedHandshakeTimeoutMs = b.moddedHandshakeTimeoutMs;
    this.neoforgeCompatMode = b.neoforgeCompatMode;
    this.legacyForgeCompatMode = b.legacyForgeCompatMode;
    this.announceModdedInPing = b.announceModdedInPing;
    this.logModHandshakes = b.logModHandshakes;

    this.writeBufferHighWatermark = b.writeBufferHighWatermark;
    this.writeBufferLowWatermark = b.writeBufferLowWatermark;
    this.smartCompressionEnabled = b.smartCompressionEnabled;
    this.smartCompressionMinSizeDelta = b.smartCompressionMinSizeDelta;
    this.packetQueueOptEnabled = b.packetQueueOptEnabled;
    this.packetQueueMaxDepth = b.packetQueueMaxDepth;
    this.connectionThrottleEnabled = b.connectionThrottleEnabled;
    this.connectionThrottleMaxPerSecond = b.connectionThrottleMaxPerSecond;

    this.diagnosticsEnabled = b.diagnosticsEnabled;
    this.traceModHandshakes = b.traceModHandshakes;
    this.slowConnectionThresholdMs = b.slowConnectionThresholdMs;
  }

  /** Loads (or generates) {@code radar.toml} from the given directory, then pushes live values. */
  public static RadarConfig load(Path configDir) {
    Path file = configDir.resolve("radar.toml");
    if (!Files.exists(file)) {
      extractDefault(file);
    }

    try (CommentedFileConfig toml = CommentedFileConfig.of(file)) {
      toml.load();
      RadarConfig cfg = fromToml(toml);
      cfg.applyLiveValues();
      return cfg;
    }
  }

  private static RadarConfig fromToml(CommentedConfig toml) {
    Builder b = new Builder();

    CommentedConfig modded = toml.get("modded");
    if (modded != null) {
      b.maxKnownPacks = modded.getIntOrElse("max-known-packs", DEFAULT_MAX_KNOWN_PACKS);
      b.handshakeCacheEnabled = modded.getOrElse("handshake-cache", true);
      b.handshakeCacheTtlSeconds = modded.getIntOrElse("handshake-cache-ttl", 300);
      b.moddedHandshakeTimeoutMs = modded.getIntOrElse("handshake-timeout-ms", 30000);
      b.neoforgeCompatMode = modded.getOrElse("neoforge-compat", true);
      b.legacyForgeCompatMode = modded.getOrElse("legacy-forge-compat", true);
      b.announceModdedInPing = modded.getOrElse("announce-modded-in-ping", false);
      b.logModHandshakes = modded.getOrElse("log-mod-handshakes", false);
    }

    CommentedConfig network = toml.get("network");
    if (network != null) {
      b.writeBufferHighWatermark = network.getIntOrElse("write-buffer-high-watermark", 2 << 20);
      b.writeBufferLowWatermark = network.getIntOrElse("write-buffer-low-watermark", 1 << 20);
      b.smartCompressionEnabled = network.getOrElse("smart-compression", true);
      b.smartCompressionMinSizeDelta = network.getIntOrElse("smart-compression-min-delta", 64);
      b.packetQueueOptEnabled = network.getOrElse("packet-queue-optimization", true);
      b.packetQueueMaxDepth = network.getIntOrElse("packet-queue-max-depth", 256);
      b.connectionThrottleEnabled = network.getOrElse("connection-throttle", true);
      b.connectionThrottleMaxPerSecond = network.getIntOrElse(
          "connection-throttle-max-per-second", 30);
    }

    CommentedConfig diag = toml.get("diagnostics");
    if (diag != null) {
      b.diagnosticsEnabled = diag.getOrElse("enabled", false);
      b.traceModHandshakes = diag.getOrElse("trace-mod-handshakes", false);
      b.slowConnectionThresholdMs = diag.getIntOrElse("slow-connection-threshold-ms", 3000);
    }

    return new RadarConfig(b);
  }

  /** Pushes config values into subsystems that cache them statically for hot-path performance. */
  private void applyLiveValues() {
    // JVM property still beats the config file — documented in radar.toml
    if (System.getProperty("velocity.max-known-packs") == null) {
      KnownPacksPacket.setMaxKnownPacks(maxKnownPacks);
      logger.info("[Conduit] max-known-packs set to {}", maxKnownPacks);
    } else {
      logger.info("[Conduit] max-known-packs overridden by JVM property: {}",
          KnownPacksPacket.getMaxKnownPacks());
    }
  }

  private static void extractDefault(Path dest) {
    try (InputStream in = RadarConfig.class.getResourceAsStream(
        "/com/velocitypowered/proxy/radar/radar.toml")) {
      if (in == null) {
        logger.error("[Conduit] Default radar.toml not found in jar — using built-in defaults.");
        return;
      }
      Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
      logger.info("[Conduit] Generated default radar.toml");
    } catch (IOException e) {
      logger.error("[Conduit] Failed to extract radar.toml: {}", e.getMessage());
    }
  }

  // ── Modded getters ────────────────────────────────────────────────────────

  /** Returns the maximum number of known packs the proxy will negotiate. */
  public int getMaxKnownPacks() {
    return maxKnownPacks;
  }

  /** Returns whether handshake caching is enabled. */
  public boolean isHandshakeCacheEnabled() {
    return handshakeCacheEnabled;
  }

  /** Returns the TTL in seconds for handshake cache entries. */
  public int getHandshakeCacheTtlSeconds() {
    return handshakeCacheTtlSeconds;
  }

  /** Returns the timeout in milliseconds for modded handshakes. */
  public int getModdedHandshakeTimeoutMs() {
    return moddedHandshakeTimeoutMs;
  }

  /** Returns whether NeoForge compatibility mode is active. */
  public boolean isNeoforgeCompatMode() {
    return neoforgeCompatMode;
  }

  /** Returns whether Legacy Forge (FML1/FML2) compatibility mode is active. */
  public boolean isLegacyForgeCompatMode() {
    return legacyForgeCompatMode;
  }

  /** Returns whether modded status is advertised in the server list ping. */
  public boolean isAnnounceModdedInPing() {
    return announceModdedInPing;
  }

  /** Returns whether mod handshake packets are logged. */
  public boolean isLogModHandshakes() {
    return logModHandshakes;
  }

  // ── Network getters ───────────────────────────────────────────────────────

  /** Returns the Netty write-buffer high watermark in bytes. */
  public int getWriteBufferHighWatermark() {
    return writeBufferHighWatermark;
  }

  /** Returns the Netty write-buffer low watermark in bytes. */
  public int getWriteBufferLowWatermark() {
    return writeBufferLowWatermark;
  }

  /** Returns whether smart compression is enabled. */
  public boolean isSmartCompressionEnabled() {
    return smartCompressionEnabled;
  }

  /** Returns the minimum byte-saving delta required to use compression. */
  public int getSmartCompressionMinSizeDelta() {
    return smartCompressionMinSizeDelta;
  }

  /** Returns whether packet-queue optimisation is enabled. */
  public boolean isPacketQueueOptEnabled() {
    return packetQueueOptEnabled;
  }

  /** Returns the maximum number of packets that may be queued per player. */
  public int getPacketQueueMaxDepth() {
    return packetQueueMaxDepth;
  }

  /** Returns whether per-IP connection throttling is enabled. */
  public boolean isConnectionThrottleEnabled() {
    return connectionThrottleEnabled;
  }

  /** Returns the maximum number of connections per IP per second. */
  public int getConnectionThrottleMaxPerSecond() {
    return connectionThrottleMaxPerSecond;
  }

  // ── Diagnostics getters ───────────────────────────────────────────────────

  /** Returns whether runtime diagnostics are enabled. */
  public boolean isDiagnosticsEnabled() {
    return diagnosticsEnabled;
  }

  /** Returns whether per-packet mod-handshake tracing is enabled. */
  public boolean isTraceModHandshakes() {
    return traceModHandshakes;
  }

  /** Returns the login duration threshold in milliseconds above which a warning is emitted. */
  public int getSlowConnectionThresholdMs() {
    return slowConnectionThresholdMs;
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  /** Mutable builder used internally by {@link #fromToml} to construct a {@link RadarConfig}. */
  private static final class Builder {
    int maxKnownPacks = DEFAULT_MAX_KNOWN_PACKS;
    boolean handshakeCacheEnabled = true;
    int handshakeCacheTtlSeconds = 300;
    int moddedHandshakeTimeoutMs = 30000;
    boolean neoforgeCompatMode = true;
    boolean legacyForgeCompatMode = true;
    boolean announceModdedInPing = false;
    boolean logModHandshakes = false;

    int writeBufferHighWatermark = 2 << 20;
    int writeBufferLowWatermark = 1 << 20;
    boolean smartCompressionEnabled = true;
    int smartCompressionMinSizeDelta = 64;
    boolean packetQueueOptEnabled = true;
    int packetQueueMaxDepth = 256;
    boolean connectionThrottleEnabled = true;
    int connectionThrottleMaxPerSecond = 30;

    boolean diagnosticsEnabled = false;
    boolean traceModHandshakes = false;
    int slowConnectionThresholdMs = 3000;
  }
}
