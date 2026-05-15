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

import com.velocitypowered.proxy.radar.diagnostics.RadarDiagnostics;
import com.velocitypowered.proxy.radar.modded.ModdedHandshakeCache;
import com.velocitypowered.proxy.radar.network.ConnectionThrottler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central lifecycle manager for Conduit extensions.
 *
 * <p>Instantiated once by the patched {@code VelocityServer} during proxy startup, before the
 * first player can connect.  All subsystems are lazily initialised here so that any startup error
 * is isolated and logged clearly.
 */
public final class Conduit {

  private static final Logger logger = LogManager.getLogger(Conduit.class);
  private static Conduit instance;

  private final RadarConfig config;
  private final ModdedHandshakeCache handshakeCache;
  private final ConnectionThrottler connectionThrottler;
  private final RadarDiagnostics diagnostics;
  private final String radarVersion;

  private Conduit(Path configDir) {
    this.radarVersion = loadVersion();
    logger.info("[Conduit] Starting Conduit v{}", radarVersion);

    this.config = RadarConfig.load(configDir);
    this.handshakeCache = config.isHandshakeCacheEnabled()
        ? new ModdedHandshakeCache(config.getHandshakeCacheTtlSeconds())
        : ModdedHandshakeCache.NOOP;
    this.connectionThrottler = config.isConnectionThrottleEnabled()
        ? new ConnectionThrottler(config.getConnectionThrottleMaxPerSecond())
        : ConnectionThrottler.UNLIMITED;
    this.diagnostics = new RadarDiagnostics(config);

    logStartupSummary();
  }

  /** Initialises Conduit. Must be called exactly once, before the first player connects. */
  public static synchronized Conduit init(Path configDir) {
    if (instance != null) {
      throw new IllegalStateException("Conduit already initialised");
    }
    instance = new Conduit(configDir);
    return instance;
  }

  /** Returns the singleton instance. Throws if {@link #init} has not been called. */
  public static Conduit get() {
    if (instance == null) {
      throw new IllegalStateException("Conduit not yet initialised");
    }
    return instance;
  }

  /** Reloads radar.toml and pushes new values into all live subsystems. */
  public void reload(Path configDir) {
    logger.info("[Conduit] Reloading configuration...");
    RadarConfig newConfig = RadarConfig.load(configDir);
    handshakeCache.setTtlSeconds(newConfig.getHandshakeCacheTtlSeconds());
    connectionThrottler.setMaxPerSecond(newConfig.getConnectionThrottleMaxPerSecond());
    diagnostics.reconfigure(newConfig);
    logger.info("[Conduit] Reload complete.");
  }

  /** Returns the loaded {@link RadarConfig}. */
  public RadarConfig getConfig() {
    return config;
  }

  /** Returns the active {@link ModdedHandshakeCache}. */
  public ModdedHandshakeCache getHandshakeCache() {
    return handshakeCache;
  }

  /** Returns the active {@link ConnectionThrottler}. */
  public ConnectionThrottler getThrottler() {
    return connectionThrottler;
  }

  /** Returns the active {@link RadarDiagnostics}. */
  public RadarDiagnostics getDiagnostics() {
    return diagnostics;
  }

  /** Returns the Conduit build version string. */
  public String getRadarVersion() {
    return radarVersion;
  }

  private void logStartupSummary() {
    logger.info("[Conduit] v{} initialised:", radarVersion);
    logger.info("[Conduit]   max-known-packs         = {}", config.getMaxKnownPacks());
    logger.info("[Conduit]   handshake-cache         = {} (TTL {}s)",
        config.isHandshakeCacheEnabled(), config.getHandshakeCacheTtlSeconds());
    logger.info("[Conduit]   neoforge-compat         = {}", config.isNeoforgeCompatMode());
    logger.info("[Conduit]   legacy-forge-compat     = {}", config.isLegacyForgeCompatMode());
    logger.info("[Conduit]   smart-compression       = {}", config.isSmartCompressionEnabled());
    logger.info("[Conduit]   packet-queue-opt        = {} (depth {})",
        config.isPacketQueueOptEnabled(), config.getPacketQueueMaxDepth());
    logger.info("[Conduit]   connection-throttle     = {} ({}/s)",
        config.isConnectionThrottleEnabled(), config.getConnectionThrottleMaxPerSecond());
    logger.info("[Conduit]   diagnostics             = {}", config.isDiagnosticsEnabled());
  }

  private static String loadVersion() {
    try (InputStream in = Conduit.class.getResourceAsStream(
        "/com/velocitypowered/proxy/radar/radar-build.properties")) {
      if (in != null) {
        Properties props = new Properties();
        props.load(in);
        return props.getProperty("conduit.version", "unknown");
      }
    } catch (IOException ignored) {
      // ignored
    }
    return "dev";
  }
}
