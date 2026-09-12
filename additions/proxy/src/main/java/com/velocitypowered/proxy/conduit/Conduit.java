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
import com.velocitypowered.proxy.conduit.diagnostics.ConduitMetricsServer;
import com.velocitypowered.proxy.conduit.forward.CommandForwarder;
import com.velocitypowered.proxy.conduit.health.BackendHealthChecker;
import com.velocitypowered.proxy.conduit.health.FallbackRouter;
import com.velocitypowered.proxy.conduit.luckperms.BundledLuckPermsInstaller;
import com.velocitypowered.proxy.conduit.luckperms.LuckPermsPermissionSeeder;
import com.velocitypowered.proxy.conduit.maintenance.MaintenanceManager;
import com.velocitypowered.proxy.conduit.modded.ModTrackerListener;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker;
import com.velocitypowered.proxy.conduit.modded.ModdedHandshakeCache;
import com.velocitypowered.proxy.conduit.motd.MotdCache;
import com.velocitypowered.proxy.conduit.network.ConnectionThrottler;
import com.velocitypowered.proxy.conduit.network.TabCompleteCache;
import com.velocitypowered.proxy.conduit.routing.ModCompatibilityRouter;
import com.velocitypowered.proxy.conduit.security.BotFilter;
import com.velocitypowered.proxy.conduit.security.ChannelGuard;
import com.velocitypowered.proxy.conduit.shutdown.GracefulShutdown;
import com.velocitypowered.proxy.conduit.spark.BundledSparkInstaller;
import com.velocitypowered.proxy.conduit.spark.BundledSparkInstaller.InstallResult;
import com.velocitypowered.proxy.conduit.update.GitHubReleaseProvider;
import com.velocitypowered.proxy.conduit.update.SemanticVersion;
import com.velocitypowered.proxy.conduit.update.UpdateChecker;
import com.velocitypowered.proxy.conduit.update.UpdateNotifier;
import com.velocitypowered.proxy.conduit.version.VersionGate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
  private final ModCompatibilityRouter modCompatibilityRouter;
  private final MaintenanceManager maintenanceManager;
  private final VersionGate versionGate;
  private final CommandForwarder commandForwarder;
  private volatile ConduitMetricsServer metricsServer;
  private final UpdateChecker updateChecker;
  private final UpdateNotifier updateNotifier;
  private volatile boolean attackModeEnabled;

  private Conduit(Path configDir) {
    this.configDir = configDir;
    this.conduitVersion = loadVersion();
    logger.info("[Conduit] Starting Conduit v{}", conduitVersion);

    this.config = ConduitConfig.load(configDir);
    this.handshakeCache = config.isHandshakeCacheEnabled()
        ? new ModdedHandshakeCache(config.getHandshakeCacheTtlSeconds())
        : ModdedHandshakeCache.NOOP;
    this.connectionThrottler = new ConnectionThrottler(
        config.getConnectionThrottleMaxPerSecond(), config.isConnectionThrottleEnabled());
    this.connectionThrottler.setPrefixes(config.getConnectionThrottleIpv4Prefix(),
        config.getConnectionThrottleIpv6Prefix());
    this.connectionThrottler.setLogIntervalMs(config.getConnectionThrottleLogIntervalMs());
    this.diagnostics = new ConduitDiagnostics(config);

    this.healthChecker = new BackendHealthChecker(config.getHealthCheckIntervalMs(),
        config.getHealthCheckFailureThreshold(), config.getHealthCheckSuccessThreshold(),
        config.isHealthCheckEnabled());
    this.fallbackRouter = FallbackRouter.DISABLED;
    this.motdCache = new MotdCache(config.getMotdCacheTtlMs(), config.isMotdCacheEnabled());
    this.gracefulShutdown = config.isGracefulShutdownEnabled()
        ? new GracefulShutdown(config.getGracefulShutdownTimeoutMs(),
            config.getGracefulShutdownMessage(),
            config.getFallbackServers())
        : null;
    this.botFilter = new BotFilter(config.getBotFilterTimeoutMs(), config.getBotFilterThreshold(),
        config.isBotFilterEnabled());
    this.botFilter.setPrefixes(config.getConnectionThrottleIpv4Prefix(),
        config.getConnectionThrottleIpv6Prefix());

    this.clientTracker = new ModdedClientTracker();
    this.clientTrackerListener = new ModTrackerListener(clientTracker);
    this.tabCompleteCache = new TabCompleteCache(config.getTabCompleteCacheTtlMs(),
        config.getTabCompleteCacheMaxEntries(), diagnostics, config.isTabCompleteCacheEnabled());
    this.channelGuard = new ChannelGuard(config.getChannelGuardBlockList(),
        ChannelGuard.Action.parse(config.getChannelGuardAction()), diagnostics,
        config.isChannelGuardEnabled());
    this.modCompatibilityRouter = new ModCompatibilityRouter(clientTracker,
        config.getModCompatibilityRules());
    this.maintenanceManager = config.isMaintenanceFeatureEnabled()
        ? new MaintenanceManager(configDir, config.isMaintenanceActiveOnStart(),
            config.getMaintenanceKickMessage(), config.getMaintenanceMotd(),
            config.getMaintenanceAllowlist())
        : MaintenanceManager.DISABLED;
    this.versionGate = new VersionGate(config.getVersionPolicy());
    this.commandForwarder = new CommandForwarder(config.getCommandForwardingChannel(),
        config.isCommandForwardingRequirePermission(), config.isCommandForwardingLog(),
        config.getCommandForwardingAllowedServers(), config.getCommandForwardingAllowlist(),
        config.getCommandForwardingDenylist(), config.isCommandForwardingEnabled());

    if (config.isUpdateCheckEnabled()) {
      GitHubReleaseProvider provider = new GitHubReleaseProvider(
          config.getUpdateRepository(),
          "Conduit/" + conduitVersion + " (+https://github.com/tame-gg/conduit)");
      this.updateChecker = new UpdateChecker(provider, conduitVersion,
          config.isUpdateIncludePrereleases(),
          config.getUpdateCacheMinutes() * 60_000L);
      this.updateNotifier = config.isUpdateNotifyOnJoin()
          ? new UpdateNotifier(updateChecker) : null;
    } else {
      this.updateChecker = null;
      this.updateNotifier = null;
    }

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
    modCompatibilityRouter.register(plugin, proxy);
    maintenanceManager.register(plugin, proxy);
    versionGate.register(plugin, proxy);
    commandForwarder.register(plugin, proxy);

    startMetricsServerIfEnabled();

    if (config.isAdminCommandsEnabled()) {
      ConduitCommand.register(proxy, plugin);
    }
    if (config.isModListCommandEnabled()) {
      ModListCommand.register(proxy, plugin);
    }

    if (updateChecker != null) {
      if (updateNotifier != null) {
        updateNotifier.register(plugin, proxy);
      }
      if (config.isUpdateNotifyOnStartup()) {
        updateChecker.checkAsync();
      }
    }

    // Publish conduit.* nodes (esp. conduit.maintenance.bypass) into LuckPerms suggestions.
    // Must run after plugins have loaded so the LuckPerms plugin instance is available.
    LuckPermsPermissionSeeder.seed(proxy);

    logger.info("[Conduit] All subsystems started against proxy.");
  }

  private void startMetricsServerIfEnabled() {
    if (!config.isMetricsHttpEnabled()) {
      return;
    }
    try {
      metricsServer = new ConduitMetricsServer(
          config.getMetricsHttpHost(),
          config.getMetricsHttpPort(),
          config.getMetricsHttpPath(),
          config.getMetricsPrometheusPath(),
          config.getMetricsAuthToken(),
          diagnostics);
      metricsServer.start();
    } catch (IOException e) {
      logger.warn("[Conduit] Failed to start metrics endpoint: {}", e.getMessage());
    }
  }

  /**
   * Installs Conduit's bundled spark Velocity plugin before Velocity scans the plugins directory.
   */
  public void installBundledSpark() {
    if (!config.isSparkBundleEnabled()) {
      logger.info("[Conduit] Bundled spark is disabled in conduit.toml.");
      return;
    }
    try {
      InstallResult result = BundledSparkInstaller.install(configDir);
      switch (result) {
        case INSTALLED -> logger.info("[Conduit] Bundled spark Velocity plugin installed.");
        case SKIPPED_EXISTING_SPARK -> logger.info("[Conduit] Existing spark plugin found;"
            + " bundled spark was not installed.");
        case SKIPPED_MISSING_RESOURCE -> logger.warn("[Conduit] Bundled spark plugin resource was"
            + " not found in the Conduit jar.");
        default -> {
          // exhaustive
        }
      }
    } catch (IOException e) {
      logger.warn("[Conduit] Failed to install bundled spark plugin: {}", e.getMessage());
    }
  }

  /**
   * Installs Conduit's bundled LuckPerms Velocity plugin before Velocity scans the plugins
   * directory, so permissions resolve natively on the same boot.
   */
  public void installBundledLuckPerms() {
    if (!config.isLuckPermsBundleEnabled()) {
      logger.info("[Conduit] Bundled LuckPerms is disabled in conduit.toml.");
      return;
    }
    try {
      BundledLuckPermsInstaller.InstallResult result =
          BundledLuckPermsInstaller.install(configDir);
      switch (result) {
        case INSTALLED -> logger.info("[Conduit] Bundled LuckPerms Velocity plugin installed.");
        case SKIPPED_EXISTING_LUCKPERMS -> logger.info("[Conduit] Existing LuckPerms plugin found;"
            + " bundled LuckPerms was not installed.");
        case SKIPPED_MISSING_RESOURCE -> logger.warn("[Conduit] Bundled LuckPerms plugin resource"
            + " was not found in the Conduit jar.");
        default -> {
          // exhaustive
        }
      }
    } catch (IOException e) {
      logger.warn("[Conduit] Failed to install bundled LuckPerms plugin: {}", e.getMessage());
    }
  }

  /**
   * Reloads conduit.toml and pushes the new values into every subsystem that can take them
   * without a restart — including turning a subsystem on or off, which earlier releases could not
   * do because a disabled subsystem was a do-nothing sentinel object chosen at boot.
   *
   * <p>What genuinely cannot be applied live is bound to something the proxy did once at startup:
   * the write-buffer watermarks (set when the listener was bound), the graceful-shutdown handler
   * (registered against the JVM shutdown hook), the metrics endpoint (a bound socket), and the
   * bundled-plugin installers (they ran before the plugin scan). Rather than printing a fixed list
   * of caveats, the reload compares the two configs and names the keys that actually changed and
   * were ignored — the list is silent when nothing of the sort changed.
   *
   * @return a human-readable summary of what was applied and what still needs a restart
   */
  public String reload(Path configDir) {
    logger.info("[Conduit] Reloading configuration...");
    ConduitConfig newConfig = ConduitConfig.load(configDir);
    ConduitConfig oldConfig = this.config;

    handshakeCache.setTtlSeconds(newConfig.getHandshakeCacheTtlSeconds());
    diagnostics.reconfigure(newConfig);
    versionGate.setPolicy(newConfig.getVersionPolicy());

    connectionThrottler.setEnabled(newConfig.isConnectionThrottleEnabled());
    connectionThrottler.setPrefixes(newConfig.getConnectionThrottleIpv4Prefix(),
        newConfig.getConnectionThrottleIpv6Prefix());
    connectionThrottler.setLogIntervalMs(newConfig.getConnectionThrottleLogIntervalMs());

    botFilter.setEnabled(newConfig.isBotFilterEnabled());
    botFilter.setHandshakeTimeoutMs(newConfig.getBotFilterTimeoutMs());
    botFilter.setPrefixes(newConfig.getConnectionThrottleIpv4Prefix(),
        newConfig.getConnectionThrottleIpv6Prefix());

    motdCache.setEnabled(newConfig.isMotdCacheEnabled());

    tabCompleteCache.setEnabled(newConfig.isTabCompleteCacheEnabled());
    tabCompleteCache.setTtlMs(newConfig.getTabCompleteCacheTtlMs());

    channelGuard.setEnabled(newConfig.isChannelGuardEnabled());
    channelGuard.reconfigure(newConfig.getChannelGuardBlockList(),
        ChannelGuard.Action.parse(newConfig.getChannelGuardAction()));

    healthChecker.setEnabled(newConfig.isHealthCheckEnabled());
    healthChecker.setIntervalMs(newConfig.getHealthCheckIntervalMs());
    healthChecker.setThresholds(newConfig.getHealthCheckFailureThreshold(),
        newConfig.getHealthCheckSuccessThreshold());

    fallbackRouter.setConfiguredFallbacks(newConfig.getFallbackServers());

    commandForwarder.setEnabled(newConfig.isCommandForwardingEnabled());
    commandForwarder.setRequirePermission(newConfig.isCommandForwardingRequirePermission());
    commandForwarder.setLogForwardedCommands(newConfig.isCommandForwardingLog());
    commandForwarder.setAllowedServers(newConfig.getCommandForwardingAllowedServers());
    commandForwarder.setCommandAllowlist(newConfig.getCommandForwardingAllowlist());
    commandForwarder.setCommandDenylist(newConfig.getCommandForwardingDenylist());

    maintenanceManager.reconfigure(newConfig.getMaintenanceKickMessage(),
        newConfig.getMaintenanceMotd(), newConfig.getMaintenanceAllowlist());

    // Attack mode overrides the live limits it owns, so re-apply it last.
    if (attackModeEnabled) {
      newConfig.getAttackModePolicy().apply(connectionThrottler, botFilter, motdCache);
    } else {
      connectionThrottler.setMaxPerSecond(newConfig.getConnectionThrottleMaxPerSecond());
      botFilter.setThreshold(newConfig.getBotFilterThreshold());
      motdCache.setTtlMs(newConfig.getMotdCacheTtlMs());
    }

    config = newConfig;
    String restartNeeded = describeRestartRequiredChanges(oldConfig, newConfig);
    if (restartNeeded.isEmpty()) {
      logger.info("[Conduit] Reload complete; every changed setting was applied live.");
      return "Conduit configuration reloaded; every changed setting was applied live.";
    }
    logger.warn("[Conduit] Reload complete, but these changes need a proxy restart: {}",
        restartNeeded);
    return "Conduit configuration reloaded. These changes need a proxy restart: " + restartNeeded;
  }

  /** Reloads using the config directory passed to {@link #init}. */
  public String reload() {
    return reload(configDir);
  }

  /**
   * Lists the keys that changed in this reload but could not be applied, so the operator hears
   * about exactly their change rather than a standing list of caveats.
   */
  private static String describeRestartRequiredChanges(ConduitConfig before, ConduitConfig after) {
    List<String> changed = new ArrayList<>();
    addIfChanged(changed, "network.write-buffer-high-watermark",
        before.getWriteBufferHighWatermark(), after.getWriteBufferHighWatermark());
    addIfChanged(changed, "network.write-buffer-low-watermark",
        before.getWriteBufferLowWatermark(), after.getWriteBufferLowWatermark());
    addIfChanged(changed, "network.tab-complete-cache-max-entries",
        before.getTabCompleteCacheMaxEntries(), after.getTabCompleteCacheMaxEntries());
    addIfChanged(changed, "server.graceful-shutdown-enabled",
        before.isGracefulShutdownEnabled(), after.isGracefulShutdownEnabled());
    addIfChanged(changed, "server.graceful-shutdown-timeout-ms",
        before.getGracefulShutdownTimeoutMs(), after.getGracefulShutdownTimeoutMs());
    addIfChanged(changed, "server.graceful-shutdown-message",
        before.getGracefulShutdownMessage(), after.getGracefulShutdownMessage());
    addIfChanged(changed, "forwarding.channel",
        before.getCommandForwardingChannel(), after.getCommandForwardingChannel());
    addIfChanged(changed, "metrics.http-enabled",
        before.isMetricsHttpEnabled(), after.isMetricsHttpEnabled());
    addIfChanged(changed, "metrics.http-host", before.getMetricsHttpHost(),
        after.getMetricsHttpHost());
    addIfChanged(changed, "metrics.http-port", before.getMetricsHttpPort(),
        after.getMetricsHttpPort());
    addIfChanged(changed, "metrics.http-path", before.getMetricsHttpPath(),
        after.getMetricsHttpPath());
    addIfChanged(changed, "metrics.prometheus-path", before.getMetricsPrometheusPath(),
        after.getMetricsPrometheusPath());
    addIfChanged(changed, "metrics.auth-token", before.getMetricsAuthToken(),
        after.getMetricsAuthToken());
    addIfChanged(changed, "maintenance.enabled", before.isMaintenanceFeatureEnabled(),
        after.isMaintenanceFeatureEnabled());
    addIfChanged(changed, "commands.admin-enabled", before.isAdminCommandsEnabled(),
        after.isAdminCommandsEnabled());
    addIfChanged(changed, "commands.modlist-enabled", before.isModListCommandEnabled(),
        after.isModListCommandEnabled());
    addIfChanged(changed, "spark.bundle-enabled", before.isSparkBundleEnabled(),
        after.isSparkBundleEnabled());
    addIfChanged(changed, "luckperms.bundle-enabled", before.isLuckPermsBundleEnabled(),
        after.isLuckPermsBundleEnabled());
    addIfChanged(changed, "update.enabled", before.isUpdateCheckEnabled(),
        after.isUpdateCheckEnabled());
    return String.join(", ", changed);
  }

  private static void addIfChanged(List<String> out, String key, Object before, Object after) {
    if (!Objects.equals(before, after)) {
      out.add(key + " (" + before + " -> " + after + ")");
    }
  }

  /** Returns the loaded {@link ConduitConfig}. */
  public ConduitConfig getConfig() {
    return config;
  }

  /** Returns the directory containing {@code conduit.toml}. */
  public Path getConfigDir() {
    return configDir;
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

  /** Returns whether stricter attack-mode runtime limits are active. */
  public boolean isAttackModeEnabled() {
    return attackModeEnabled;
  }

  /** Enables stricter live runtime limits for bot-flood mitigation. */
  public void enableAttackMode() {
    config.getAttackModePolicy().apply(connectionThrottler, botFilter, motdCache);
    attackModeEnabled = true;
    logger.warn("[Conduit] Attack mode enabled.");
  }

  /** Restores live runtime limits from {@code conduit.toml}. */
  public void disableAttackMode() {
    config.getAttackModePolicy().restore(
        connectionThrottler,
        botFilter,
        motdCache,
        config.getConnectionThrottleMaxPerSecond(),
        config.getBotFilterThreshold(),
        config.getMotdCacheTtlMs());
    attackModeEnabled = false;
    logger.info("[Conduit] Attack mode disabled.");
  }

  /** Returns the active {@link ChannelGuard}. */
  public ChannelGuard getChannelGuard() {
    return channelGuard;
  }

  /** Returns the {@link VersionGate} enforcing the advertised client-version range. */
  public VersionGate getVersionGate() {
    return versionGate;
  }

  /** Returns the active {@link MaintenanceManager}. */
  public MaintenanceManager getMaintenanceManager() {
    return maintenanceManager;
  }

  /** Returns the active {@link CommandForwarder}. */
  public CommandForwarder getCommandForwarder() {
    return commandForwarder;
  }

  /** Returns the Conduit build version string. */
  public String getConduitVersion() {
    return conduitVersion;
  }

  /**
   * Returns whether this is a genuine development build of Conduit.
   *
   * <p>Unlike the inherited upstream {@code ProxyVersion#isDevelopmentVersion()} — which keys off
   * the Velocity {@code -SNAPSHOT} version string and is therefore always {@code true} for Conduit
   * regardless of release status — this is driven by Conduit's own version metadata. A released or
   * normally-built jar embeds a real semantic {@code conduit.version} (e.g. {@code 1.4.0}); only a
   * build with no embedded version (the {@code dev} fallback, i.e. run without the generated
   * {@code conduit-build.properties}) is treated as a development build.
   *
   * @return {@code true} only when no semantic Conduit version is embedded
   */
  public boolean isDevelopmentBuild() {
    return SemanticVersion.parse(conduitVersion).isEmpty();
  }

  /**
   * Returns the update checker, or {@code null} when update checking is disabled in
   * {@code conduit.toml}.
   */
  public UpdateChecker getUpdateChecker() {
    return updateChecker;
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
    ConduitMetricsServer server = metricsServer;
    if (server != null) {
      try {
        server.close();
      } catch (RuntimeException e) {
        logger.warn("[Conduit] Error closing metrics endpoint: {}", e.getMessage());
      }
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
    logger.info("[Conduit]   maintenance             = {} (active {}, {} allow-listed)",
        config.isMaintenanceFeatureEnabled(), maintenanceManager.isActive(),
        config.getMaintenanceAllowlist().size());
    logger.info("[Conduit]   versions                = {} (allowing {})",
        config.getVersionPolicy().isEnabled() ? "restricted" : "unrestricted",
        config.getVersionPolicy().getVersionsLabel());
    logger.info("[Conduit]   admin-commands          = {}", config.isAdminCommandsEnabled());
    logger.info("[Conduit]   modlist-command         = {}", config.isModListCommandEnabled());
    logger.info("[Conduit]   update-check            = {} (repo {}, notify-join {})",
        config.isUpdateCheckEnabled(), config.getUpdateRepository(),
        config.isUpdateNotifyOnJoin());
    logger.info("[Conduit]   command-forwarding      = {} (channel {}, require-perm {})",
        config.isCommandForwardingEnabled(), config.getCommandForwardingChannel(),
        config.isCommandForwardingRequirePermission());
    logger.info("[Conduit]   seamless-server-switches = {} (experimental; 1.20.2+)",
        config.isSeamlessServerSwitches());
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
