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

package com.velocitypowered.proxy.conduit;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.conduit.command.ConduitCommand;
import com.velocitypowered.proxy.conduit.command.ModListCommand;
import com.velocitypowered.proxy.conduit.diagnostics.ConduitDiagnostics;
import com.velocitypowered.proxy.conduit.health.BackendHealthChecker;
import com.velocitypowered.proxy.conduit.health.FallbackRouter;
import com.velocitypowered.proxy.conduit.modded.ModTrackerListener;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker;
import com.velocitypowered.proxy.conduit.modded.ModdedHandshakeCache;
import com.velocitypowered.proxy.conduit.motd.MotdCache;
import com.velocitypowered.proxy.conduit.network.ConnectionThrottler;
import com.velocitypowered.proxy.conduit.network.TabCompleteCache;
import com.velocitypowered.proxy.conduit.security.BotFilter;
import com.velocitypowered.proxy.conduit.security.ChannelGuard;
import com.velocitypowered.proxy.conduit.shutdown.GracefulShutdown;
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
  private static volatile Conduit instance;

  private final Path configDir;
  private volatile ConduitConfig config;
  private final ModdedHandshakeCache handshakeCache;
  private final ConnectionThrottler connectionThrottler;
  private final ConduitDiagnostics diagnostics;
  private final String conduitVersion;
  private final BackendHealthChecker healthChecker;
  private volatile FallbackRouter fallbackRouter;
  private final MotdCache motdCache;
  private final GracefulShutdown gracefulShutdown;
  private final BotFilter botFilter;
  private final ModdedClientTracker clientTracker;
  private final ModTrackerListener clientTrackerListener;
  private final TabCompleteCache tabCompleteCache;
  private final ChannelGuard channelGuard;

  private Conduit(Path configDir) {
    this.configDir = configDir;
    this.conduitVersion = loadVersion();
    logger.info("[Conduit] Starting Conduit v{}", conduitVersion);

    this.config = ConduitConfig.load(configDir);
    this.handshakeCache = config.isHandshakeCacheEnabled()
        ? new ModdedHandshakeCache(config.getHandshakeCacheTtlSeconds())
        : ModdedHandshakeCache.NOOP;
    this.connectionThrottler = config.isConnectionThrottleEnabled()
        ? new ConnectionThrottler(config.getConnectionThrottleMaxPerSecond())
        : ConnectionThrottler.UNLIMITED;
    this.diagnostics = new ConduitDiagnostics(config);

    this.healthChecker = config.isHealthCheckEnabled()
        ? new BackendHealthChecker(config.getHealthCheckIntervalMs())
        : BackendHealthChecker.DISABLED;
    this.fallbackRouter = FallbackRouter.DISABLED;
    this.motdCache = config.isMotdCacheEnabled()
        ? new MotdCache(config.getMotdCacheTtlMs())
        : MotdCache.DISABLED;
    this.gracefulShutdown = config.isGracefulShutdownEnabled()
        ? new GracefulShutdown(config.getGracefulShutdownTimeoutMs(),
            config.getGracefulShutdownMessage(),
            config.getFallbackServers())
        : null;
    this.botFilter = config.isBotFilterEnabled()
        ? new BotFilter(config.getBotFilterTimeoutMs(), config.getBotFilterThreshold())
        : BotFilter.DISABLED;

    this.clientTracker = new ModdedClientTracker();
    this.clientTrackerListener = new ModTrackerListener(clientTracker);
    this.tabCompleteCache = config.isTabCompleteCacheEnabled()
        ? new TabCompleteCache(config.getTabCompleteCacheTtlMs(),
            config.getTabCompleteCacheMaxEntries(), diagnostics)
        : TabCompleteCache.DISABLED;
    this.channelGuard = config.isChannelGuardEnabled()
        ? new ChannelGuard(config.getChannelGuardBlockList(),
            ChannelGuard.Action.parse(config.getChannelGuardAction()), diagnostics)
        : ChannelGuard.DISABLED;

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

  /**
   * Starts all subsystems that require a {@link ProxyServer} reference (event listeners, health
   * checker, shutdown hook).  Must be called after the proxy has finished its own startup.
   *
   * @param proxy the fully-initialised proxy server
   */
  public void start(ProxyServer proxy) {
    healthChecker.start(proxy);

    Object plugin = proxy.getPluginManager()
        .getPlugin("conduit")
        .flatMap(PluginContainer::getInstance)
        .orElseThrow(() -> new IllegalStateException(
            "[Conduit] Could not find conduit plugin container for event registration"));

    this.fallbackRouter = new FallbackRouter(healthChecker, config.getFallbackServers(), proxy);
    fallbackRouter.register(plugin, proxy);

    motdCache.register(plugin, proxy);

    if (gracefulShutdown != null) {
      gracefulShutdown.register(proxy);
    }

    clientTrackerListener.register(plugin, proxy);

    channelGuard.register(plugin, proxy);

    if (config.isAdminCommandsEnabled()) {
      ConduitCommand.register(proxy, plugin);
    }
    if (config.isModListCommandEnabled()) {
      ModListCommand.register(proxy, plugin);
    }

    logger.info("[Conduit] All subsystems started against proxy.");
  }

  /**
   * Reloads conduit.toml and pushes new values into the subsystems that support live tuning.
   *
   * <p>Live-tunable: handshake cache TTL, connection throttler rate, diagnostics flags, max known
   * packs (via {@link ConduitConfig#applyLiveValues}).
   *
   * <p>Restart-required: write-buffer watermarks (bound at listener bind time), backend health-check
   * interval, MOTD TTL, graceful-shutdown configuration, bot-filter thresholds, fallback-server
   * list. These are logged as ignored so operators are not misled.
   */
  public void reload(Path configDir) {
    logger.info("[Conduit] Reloading configuration...");
    ConduitConfig newConfig = ConduitConfig.load(configDir);
    handshakeCache.setTtlSeconds(newConfig.getHandshakeCacheTtlSeconds());
    connectionThrottler.setMaxPerSecond(newConfig.getConnectionThrottleMaxPerSecond());
    diagnostics.reconfigure(newConfig);
    config = newConfig;
    logger.info("[Conduit] Reload complete. Note: write-buffer watermarks, health-check interval,"
        + " MOTD TTL, graceful-shutdown, bot-filter, tab-complete cache, and channel-guard"
        + " settings require a proxy restart.");
  }

  /** Reloads using the config directory passed to {@link #init}. */
  public void reload() {
    reload(configDir);
  }

  /** Returns the loaded {@link ConduitConfig}. */
  public ConduitConfig getConfig() {
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

  /** Returns the active {@link ConduitDiagnostics}. */
  public ConduitDiagnostics getDiagnostics() {
    return diagnostics;
  }

  /** Returns the active {@link BackendHealthChecker}. */
  public BackendHealthChecker getHealthChecker() {
    return healthChecker;
  }

  /** Returns the active {@link FallbackRouter}. */
  public FallbackRouter getFallbackRouter() {
    return fallbackRouter;
  }

  /** Returns the active {@link MotdCache}. */
  public MotdCache getMotdCache() {
    return motdCache;
  }

  /**
   * Returns the active {@link GracefulShutdown} handler, or {@code null} if graceful shutdown is
   * disabled.
   */
  public GracefulShutdown getGracefulShutdown() {
    return gracefulShutdown;
  }

  /** Returns the active {@link BotFilter}. */
  public BotFilter getBotFilter() {
    return botFilter;
  }

  /** Returns the active {@link ModdedClientTracker}. */
  public ModdedClientTracker getClientTracker() {
    return clientTracker;
  }

  /** Returns the listener that feeds {@link ModdedClientTracker} from public events. */
  public ModTrackerListener getClientTrackerListener() {
    return clientTrackerListener;
  }

  /** Returns the active {@link TabCompleteCache}. */
  public TabCompleteCache getTabCompleteCache() {
    return tabCompleteCache;
  }

  /** Returns the active {@link ChannelGuard}. */
  public ChannelGuard getChannelGuard() {
    return channelGuard;
  }

  /** Returns the Conduit build version string. */
  public String getConduitVersion() {
    return conduitVersion;
  }

  /**
   * Stops background work owned by Conduit (health-checker scheduler, etc.).
   * Idempotent and safe to call even if {@link #start} was never invoked.
   * Intended to be called from {@code VelocityServer.shutdown()}.
   */
  public void shutdown() {
    try {
      healthChecker.stop();
    } catch (RuntimeException e) {
      logger.warn("[Conduit] Error during shutdown: {}", e.getMessage());
    }
  }

  private void logStartupSummary() {
    logger.info("[Conduit] v{} initialised:", conduitVersion);
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
    logger.info("[Conduit]   health-check            = {} (interval {}ms)",
        config.isHealthCheckEnabled(), config.getHealthCheckIntervalMs());
    logger.info("[Conduit]   fallback-servers        = {}", config.getFallbackServers());
    logger.info("[Conduit]   motd-cache              = {} (TTL {}ms)",
        config.isMotdCacheEnabled(), config.getMotdCacheTtlMs());
    logger.info("[Conduit]   graceful-shutdown       = {} (timeout {}ms)",
        config.isGracefulShutdownEnabled(), config.getGracefulShutdownTimeoutMs());
    logger.info("[Conduit]   bot-filter              = {} (timeout {}ms, threshold {})",
        config.isBotFilterEnabled(), config.getBotFilterTimeoutMs(),
        config.getBotFilterThreshold());
    logger.info("[Conduit]   tab-complete-cache      = {} (TTL {}ms, max {})",
        config.isTabCompleteCacheEnabled(), config.getTabCompleteCacheTtlMs(),
        config.getTabCompleteCacheMaxEntries());
    logger.info("[Conduit]   channel-guard           = {} (action {}, {} patterns)",
        config.isChannelGuardEnabled(), config.getChannelGuardAction(),
        config.getChannelGuardBlockList().size());
    logger.info("[Conduit]   admin-commands          = {}", config.isAdminCommandsEnabled());
    logger.info("[Conduit]   modlist-command         = {}", config.isModListCommandEnabled());
  }

  private static String loadVersion() {
    try (InputStream in = Conduit.class.getResourceAsStream(
        "/com/velocitypowered/proxy/conduit/conduit-build.properties")) {
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
