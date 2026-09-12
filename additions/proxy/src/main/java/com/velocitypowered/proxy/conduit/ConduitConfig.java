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

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.conduit.health.BackendHealthChecker;
import com.velocitypowered.proxy.conduit.network.ConnectionThrottler;
import com.velocitypowered.proxy.conduit.routing.ModCompatibilityRules;
import com.velocitypowered.proxy.conduit.security.AttackModePolicy;
import com.velocitypowered.proxy.conduit.security.ChannelGuardPreset;
import com.velocitypowered.proxy.conduit.version.VersionPolicy;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads and exposes all Conduit-specific settings from {@code conduit.toml}.
 *
 * <p>Keeping this in a separate file (rather than patching velocity.toml) means upstream
 * VelocityConfiguration can be merged without conflicts.
 */
public final class ConduitConfig {

  private static final Logger logger = LogManager.getLogger(ConduitConfig.class);
  public static final int DEFAULT_MAX_KNOWN_PACKS = 1024;

  /** Default server-list label shown to clients outside the configured version range. */
  static final String DEFAULT_PING_VERSION_NAME = "Conduit {versions}";

  /** Default kick text when exactly one Minecraft version is allowed. */
  static final String DEFAULT_VERSION_KICK_MESSAGE =
      "<red>This network only allows players to join on version <white>{versions}</white>.";

  /** Default kick text when a range of Minecraft versions is allowed. */
  static final String DEFAULT_VERSION_KICK_MESSAGE_RANGE =
      "<red>This network only allows players to join on versions <white>{versions}</white>.";

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

  // ── Server section ────────────────────────────────────────────────────────
  private final boolean healthCheckEnabled;
  private final int healthCheckIntervalMs;
  private final int healthCheckFailureThreshold;
  private final int healthCheckSuccessThreshold;
  private final List<String> fallbackServers;
  private final boolean motdCacheEnabled;
  private final int motdCacheTtlMs;
  private final boolean gracefulShutdownEnabled;
  private final int gracefulShutdownTimeoutMs;
  private final String gracefulShutdownMessage;
  private final boolean botFilterEnabled;
  private final int botFilterTimeoutMs;
  private final int botFilterThreshold;

  // ── Network (continued) ───────────────────────────────────────────────────
  private final int connectionThrottleIpv4Prefix;
  private final int connectionThrottleIpv6Prefix;
  private final int connectionThrottleLogIntervalMs;
  private final boolean tabCompleteCacheEnabled;
  private final int tabCompleteCacheTtlMs;
  private final int tabCompleteCacheMaxEntries;

  // ── Security section ──────────────────────────────────────────────────────
  private final boolean channelGuardEnabled;
  private final ChannelGuardPreset channelGuardPreset;
  private final String channelGuardAction;
  private final List<String> channelGuardBlockList;
  private final AttackModePolicy attackModePolicy;

  // ── Routing section ───────────────────────────────────────────────────────
  private final ModCompatibilityRules modCompatibilityRules;

  // ── Metrics section ───────────────────────────────────────────────────────
  private final boolean metricsHttpEnabled;
  private final String metricsHttpHost;
  private final int metricsHttpPort;
  private final String metricsHttpPath;
  private final String metricsPrometheusPath;
  private final String metricsAuthToken;

  // ── Maintenance section ───────────────────────────────────────────────────
  private final boolean maintenanceFeatureEnabled;
  private final boolean maintenanceActiveOnStart;
  private final String maintenanceKickMessage;
  private final String maintenanceMotd;
  private final List<String> maintenanceAllowlist;

  // ── Versions section ──────────────────────────────────────────────────────
  private final VersionPolicy versionPolicy;

  // ── Commands section ──────────────────────────────────────────────────────
  private final boolean adminCommandsEnabled;
  private final boolean modListCommandEnabled;

  // ── Forwarding section ────────────────────────────────────────────────────
  private final boolean commandForwardingEnabled;
  private final String commandForwardingChannel;
  private final boolean commandForwardingRequirePermission;
  private final boolean commandForwardingLog;
  private final List<String> commandForwardingAllowedServers;
  private final List<String> commandForwardingAllowlist;
  private final List<String> commandForwardingDenylist;

  // ── Update section ────────────────────────────────────────────────────────
  private final boolean updateCheckEnabled;
  private final boolean updateNotifyOnStartup;
  private final boolean updateNotifyOnJoin;
  private final String updateRepository;
  private final boolean updateIncludePrereleases;
  private final int updateCacheMinutes;

  // ── Spark section ─────────────────────────────────────────────────────────
  private final boolean sparkBundleEnabled;

  // ── LuckPerms section ─────────────────────────────────────────────────────
  private final boolean luckPermsBundleEnabled;

  // ── Advanced section ──────────────────────────────────────────────────────
  private final boolean seamlessServerSwitches;
  private final int seamlessSwitchSettleMs;
  private final boolean seamlessSwitchSoundEnabled;
  private final String seamlessSwitchSound;
  private final float seamlessSwitchSoundVolume;
  private final float seamlessSwitchSoundPitch;

  private ConduitConfig(Builder b) {
    validate(b);
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

    this.healthCheckEnabled = b.healthCheckEnabled;
    this.healthCheckIntervalMs = b.healthCheckIntervalMs;
    this.healthCheckFailureThreshold = b.healthCheckFailureThreshold;
    this.healthCheckSuccessThreshold = b.healthCheckSuccessThreshold;
    this.fallbackServers = b.fallbackServers;
    this.motdCacheEnabled = b.motdCacheEnabled;
    this.motdCacheTtlMs = b.motdCacheTtlMs;
    this.gracefulShutdownEnabled = b.gracefulShutdownEnabled;
    this.gracefulShutdownTimeoutMs = b.gracefulShutdownTimeoutMs;
    this.gracefulShutdownMessage = b.gracefulShutdownMessage;
    this.botFilterEnabled = b.botFilterEnabled;
    this.botFilterTimeoutMs = b.botFilterTimeoutMs;
    this.botFilterThreshold = b.botFilterThreshold;

    this.connectionThrottleIpv4Prefix = b.connectionThrottleIpv4Prefix;
    this.connectionThrottleIpv6Prefix = b.connectionThrottleIpv6Prefix;
    this.connectionThrottleLogIntervalMs = b.connectionThrottleLogIntervalMs;
    this.tabCompleteCacheEnabled = b.tabCompleteCacheEnabled;
    this.tabCompleteCacheTtlMs = b.tabCompleteCacheTtlMs;
    this.tabCompleteCacheMaxEntries = b.tabCompleteCacheMaxEntries;

    this.channelGuardEnabled = b.channelGuardEnabled;
    this.channelGuardPreset = b.channelGuardPreset;
    this.channelGuardAction = b.channelGuardAction;
    this.channelGuardBlockList = b.channelGuardBlockList;
    this.attackModePolicy = b.attackModePolicy;

    this.modCompatibilityRules = b.modCompatibilityRules;

    this.metricsHttpEnabled = b.metricsHttpEnabled;
    this.metricsHttpHost = b.metricsHttpHost;
    this.metricsHttpPort = b.metricsHttpPort;
    this.metricsHttpPath = b.metricsHttpPath;
    this.metricsPrometheusPath = b.metricsPrometheusPath;
    this.metricsAuthToken = b.metricsAuthToken;

    this.maintenanceFeatureEnabled = b.maintenanceFeatureEnabled;
    this.maintenanceActiveOnStart = b.maintenanceActiveOnStart;
    this.maintenanceKickMessage = b.maintenanceKickMessage;
    this.maintenanceMotd = b.maintenanceMotd;
    this.maintenanceAllowlist = b.maintenanceAllowlist;

    this.versionPolicy = b.versionPolicy;

    this.adminCommandsEnabled = b.adminCommandsEnabled;
    this.modListCommandEnabled = b.modListCommandEnabled;

    this.commandForwardingEnabled = b.commandForwardingEnabled;
    this.commandForwardingChannel = b.commandForwardingChannel;
    this.commandForwardingRequirePermission = b.commandForwardingRequirePermission;
    this.commandForwardingLog = b.commandForwardingLog;
    this.commandForwardingAllowedServers = b.commandForwardingAllowedServers;
    this.commandForwardingAllowlist = b.commandForwardingAllowlist;
    this.commandForwardingDenylist = b.commandForwardingDenylist;

    this.updateCheckEnabled = b.updateCheckEnabled;
    this.updateNotifyOnStartup = b.updateNotifyOnStartup;
    this.updateNotifyOnJoin = b.updateNotifyOnJoin;
    this.updateRepository = b.updateRepository;
    this.updateIncludePrereleases = b.updateIncludePrereleases;
    this.updateCacheMinutes = b.updateCacheMinutes;

    this.sparkBundleEnabled = b.sparkBundleEnabled;

    this.luckPermsBundleEnabled = b.luckPermsBundleEnabled;

    this.seamlessServerSwitches = b.seamlessServerSwitches;
    this.seamlessSwitchSettleMs = b.seamlessSwitchSettleMs;
    this.seamlessSwitchSoundEnabled = b.seamlessSwitchSoundEnabled;
    this.seamlessSwitchSound = b.seamlessSwitchSound;
    this.seamlessSwitchSoundVolume = b.seamlessSwitchSoundVolume;
    this.seamlessSwitchSoundPitch = b.seamlessSwitchSoundPitch;
  }

  /**
   * Loads (or generates) {@code conduit.toml} from the given directory, then pushes live values.
   *
   * <p>If {@code conduit.toml} does not exist but a legacy {@code radar.toml} is present (from
   * Conduit v1.0.x), the file is renamed automatically so existing configuration is preserved.
   */
  public static ConduitConfig load(Path configDir) {
    return load(configDir, true);
  }

  private static ConduitConfig load(Path configDir, boolean applyLiveValues) {
    Path file = configDir.resolve("conduit.toml");
    if (!Files.exists(file)) {
      Path legacy = configDir.resolve("radar.toml");
      if (Files.exists(legacy)) {
        try {
          Files.move(legacy, file);
          logger.info("[Conduit] Renamed radar.toml → conduit.toml (one-time migration).");
        } catch (IOException e) {
          logger.warn("[Conduit] Could not rename radar.toml to conduit.toml: {}", e.getMessage());
          file = legacy;
        }
      } else {
        extractDefault(file);
      }
    }

    // Bring an existing file forward: add any options introduced by newer Conduit versions with
    // their defaults, without touching values the operator already set. A freshly-extracted file
    // is already complete, so this is a no-op on first run.
    if (file.getFileName().toString().equals("conduit.toml")) {
      ConduitConfigMigrator.migrate(file);
    }

    // We construct the FileConfig without try-with-resources because CommentedFileConfig#close()
    // will write back to disk, reformatting the user's file. We only want to read.
    CommentedFileConfig toml = CommentedFileConfig.of(file);
    try {
      try {
        toml.load();
      } catch (ParsingException malformed) {
        throw new IllegalArgumentException(describeParseFailure(file, malformed), malformed);
      }
      ConduitConfig cfg = fromToml(toml);
      if (applyLiveValues) {
        cfg.applyLiveValues();
      }
      return cfg;
    } finally {
      // Close the underlying channels but don't trigger a save.
      try {
        toml.close();
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
  }

  /**
   * Turns a TOML parser exception into something an operator can act on.
   *
   * <p>The underlying message names neither the file nor the line, and its wording is an artefact
   * of how the parser backtracks — an unquoted word in a list is reported as a malformed
   * <em>number</em>, because a bare token can only legally be one. That is the mistake operators
   * actually make, so it gets called out by name.
   */
  private static String describeParseFailure(Path file, ParsingException cause) {
    StringBuilder message = new StringBuilder()
        .append(file.toAbsolutePath())
        .append(" is not valid TOML: ")
        .append(cause.getMessage());
    if (cause.getMessage() != null && cause.getMessage().contains("in number")) {
      message.append(". A bare word where a value is expected is the usual cause — strings must be"
          + " quoted, in lists too (fallback-servers = [\"lobby\", \"hardcore\"], not"
          + " [lobby, hardcore])");
    }
    return message.append('.').toString();
  }

  /** Loads {@code conduit.toml} for inspection without mutating live static values. */
  public static ConduitConfig loadPreview(Path configDir) {
    return load(configDir, false);
  }

  private static ConduitConfig fromToml(CommentedConfig toml) {
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
      b.connectionThrottleIpv4Prefix = network.getIntOrElse(
          "connection-throttle-ipv4-prefix", ConnectionThrottler.DEFAULT_IPV4_PREFIX);
      b.connectionThrottleIpv6Prefix = network.getIntOrElse(
          "connection-throttle-ipv6-prefix", ConnectionThrottler.DEFAULT_IPV6_PREFIX);
      b.connectionThrottleLogIntervalMs = network.getIntOrElse(
          "connection-throttle-log-interval-ms",
          (int) ConnectionThrottler.DEFAULT_LOG_INTERVAL_MS);
      b.tabCompleteCacheEnabled = network.getOrElse("tab-complete-cache", false);
      b.tabCompleteCacheTtlMs = network.getIntOrElse("tab-complete-cache-ttl-ms", 1500);
      b.tabCompleteCacheMaxEntries = network.getIntOrElse(
          "tab-complete-cache-max-entries", 1024);
    }

    CommentedConfig security = toml.get("security");
    if (security != null) {
      b.channelGuardEnabled = security.getOrElse("channel-guard", false);
      b.channelGuardPreset = ChannelGuardPreset.parse(
          security.getOrElse("channel-guard-preset", "custom"));
      b.channelGuardAction = security.getOrElse("channel-guard-action",
          b.channelGuardPreset.defaultAction().name().toLowerCase());
      b.channelGuardBlockList = security.getOrElse("channel-guard-block-list",
          b.channelGuardPreset == ChannelGuardPreset.CUSTOM
              ? Builder.DEFAULT_CHANNEL_BLOCK_LIST
              : b.channelGuardPreset.blockList());
      b.attackModePolicy = new AttackModePolicy(
          security.getIntOrElse("attack-mode-connection-throttle-max-per-second", 8),
          security.getIntOrElse("attack-mode-bot-filter-threshold", 3),
          security.getIntOrElse("attack-mode-motd-cache-ttl-ms", 10000));
    }

    CommentedConfig routing = toml.get("routing");
    if (routing != null) {
      b.modCompatibilityRules = ModCompatibilityRules.parse(
          routing.getOrElse("mod-compatibility", Collections.emptyList()));
    }

    CommentedConfig metrics = toml.get("metrics");
    if (metrics != null) {
      b.metricsHttpEnabled = metrics.getOrElse("http-enabled", false);
      b.metricsHttpHost = metrics.getOrElse("http-host", "127.0.0.1");
      b.metricsHttpPort = metrics.getIntOrElse("http-port", 9589);
      b.metricsHttpPath = metrics.getOrElse("http-path", "/metrics");
      b.metricsPrometheusPath = metrics.getOrElse("prometheus-path", "/metrics/prometheus");
      b.metricsAuthToken = metrics.getOrElse("auth-token", "");
    }

    CommentedConfig maintenance = toml.get("maintenance");
    if (maintenance != null) {
      b.maintenanceFeatureEnabled = maintenance.getOrElse("enabled", true);
      b.maintenanceActiveOnStart = maintenance.getOrElse("active-on-start", false);
      b.maintenanceKickMessage = maintenance.getOrElse("kick-message",
          "<red>The network is currently down for maintenance.\n"
              + "<gray>Please check back soon.");
      b.maintenanceMotd = maintenance.getOrElse("motd",
          "<red><bold>⚠ Maintenance</bold></red>\n<gray>The network is temporarily offline.");
      b.maintenanceAllowlist = maintenance.getOrElse("allowlist", Collections.emptyList());
    }

    CommentedConfig versions = toml.get("versions");
    if (versions != null) {
      b.versionPolicy = new VersionPolicy(
          versions.getOrElse("enabled", false),
          parseVersionList(versions.getOrElse("allow", Collections.emptyList())),
          VersionPolicy.parseVersion("versions.minimum",
              asVersionString(versions.get("minimum"))),
          VersionPolicy.parseVersion("versions.maximum",
              asVersionString(versions.get("maximum"))),
          versions.getOrElse("ping-version-name", DEFAULT_PING_VERSION_NAME),
          versions.getOrElse("kick-message", DEFAULT_VERSION_KICK_MESSAGE),
          versions.getOrElse("kick-message-range", DEFAULT_VERSION_KICK_MESSAGE_RANGE));
    }

    CommentedConfig commands = toml.get("commands");
    if (commands != null) {
      b.adminCommandsEnabled = commands.getOrElse("admin-enabled", true);
      b.modListCommandEnabled = commands.getOrElse("modlist-enabled", true);
    }

    CommentedConfig forwarding = toml.get("forwarding");
    if (forwarding != null) {
      b.commandForwardingEnabled = forwarding.getOrElse("command-forwarding", false);
      b.commandForwardingChannel = forwarding.getOrElse("channel",
          "velocity_command_forward:main");
      b.commandForwardingRequirePermission = forwarding.getOrElse("require-permission", false);
      b.commandForwardingLog = forwarding.getOrElse("log-forwarded-commands", true);
      b.commandForwardingAllowedServers =
          forwarding.getOrElse("allowed-servers", Collections.emptyList());
      b.commandForwardingAllowlist =
          forwarding.getOrElse("command-allowlist", Collections.emptyList());
      b.commandForwardingDenylist =
          forwarding.getOrElse("command-denylist", Collections.emptyList());
    }

    CommentedConfig update = toml.get("update");
    if (update != null) {
      b.updateCheckEnabled = update.getOrElse("enabled", true);
      b.updateNotifyOnStartup = update.getOrElse("notify-on-startup", true);
      b.updateNotifyOnJoin = update.getOrElse("notify-on-join", true);
      b.updateRepository = update.getOrElse("github-repository", "tame-gg/conduit");
      b.updateIncludePrereleases = update.getOrElse("include-prereleases", false);
      b.updateCacheMinutes = update.getIntOrElse("cache-minutes", 360);
    }

    CommentedConfig spark = toml.get("spark");
    if (spark != null) {
      b.sparkBundleEnabled = spark.getOrElse("bundle-enabled", true);
    }

    CommentedConfig luckperms = toml.get("luckperms");
    if (luckperms != null) {
      b.luckPermsBundleEnabled = luckperms.getOrElse("bundle-enabled", true);
    }

    CommentedConfig advanced = toml.get("advanced");
    if (advanced != null) {
      b.seamlessServerSwitches = advanced.getOrElse("seamless-server-switches", false);
      b.seamlessSwitchSettleMs = advanced.getIntOrElse("seamless-switch-settle-ms", 250);
      b.seamlessSwitchSoundEnabled = advanced.getOrElse("seamless-switch-sound-enabled", true);
      b.seamlessSwitchSound =
          advanced.getOrElse("seamless-switch-sound", "minecraft:entity.enderman.teleport");
      Object volObj = advanced.getOrElse("seamless-switch-sound-volume", 1.0);
      b.seamlessSwitchSoundVolume = volObj instanceof Number n ? n.floatValue() : 1.0f;
      Object pitchObj = advanced.getOrElse("seamless-switch-sound-pitch", 1.0);
      b.seamlessSwitchSoundPitch = pitchObj instanceof Number n ? n.floatValue() : 1.0f;
    }

    CommentedConfig diag = toml.get("diagnostics");
    if (diag != null) {
      b.diagnosticsEnabled = diag.getOrElse("enabled", false);
      b.traceModHandshakes = diag.getOrElse("trace-mod-handshakes", false);
      b.slowConnectionThresholdMs = diag.getIntOrElse("slow-connection-threshold-ms", 3000);
    }

    CommentedConfig server = toml.get("server");
    if (server != null) {
      b.healthCheckEnabled = server.getOrElse("health-check-enabled", true);
      b.healthCheckIntervalMs = server.getIntOrElse("health-check-interval-ms", 10000);
      b.healthCheckFailureThreshold = server.getIntOrElse("health-check-failure-threshold",
          BackendHealthChecker.DEFAULT_FAILURE_THRESHOLD);
      b.healthCheckSuccessThreshold = server.getIntOrElse("health-check-success-threshold",
          BackendHealthChecker.DEFAULT_SUCCESS_THRESHOLD);
      b.fallbackServers = server.getOrElse("fallback-servers", Collections.emptyList());
      b.motdCacheEnabled = server.getOrElse("motd-cache-enabled", true);
      b.motdCacheTtlMs = server.getIntOrElse("motd-cache-ttl-ms", 2000);
      b.gracefulShutdownEnabled = server.getOrElse("graceful-shutdown-enabled", true);
      b.gracefulShutdownTimeoutMs = server.getIntOrElse("graceful-shutdown-timeout-ms", 5000);
      b.gracefulShutdownMessage = server.getOrElse("graceful-shutdown-message",
          "Proxy is restarting. Please reconnect in a moment.");
      b.botFilterEnabled = server.getOrElse("bot-filter-enabled", true);
      b.botFilterTimeoutMs = server.getIntOrElse("bot-filter-timeout-ms", 3000);
      b.botFilterThreshold = server.getIntOrElse("bot-filter-threshold", 10);
    }

    return new ConduitConfig(b);
  }

  /**
   * Resolves the {@code versions.allow} list, which names individual versions rather than a range.
   *
   * <p>Entries may be version names or protocol numbers, in any order, and TOML gives back either
   * strings or integers depending on how they were written — all of which
   * {@link #asVersionString} and {@link VersionPolicy#parseVersion} already handle.
   */
  private static List<ProtocolVersion> parseVersionList(List<?> configured) {
    if (configured == null || configured.isEmpty()) {
      return Collections.emptyList();
    }
    List<ProtocolVersion> parsed = new ArrayList<>(configured.size());
    for (Object entry : configured) {
      String value = asVersionString(entry);
      if (!value.isBlank()) {
        parsed.add(VersionPolicy.parseVersion("versions.allow", value));
      }
    }
    return parsed;
  }

  /**
   * Renders a configured version bound as a string.
   *
   * <p>TOML types both {@code minimum = "774"} and {@code minimum = 774}; operators reach for
   * either when pinning by protocol number, so accept both rather than failing with a cast error.
   */
  private static String asVersionString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /** Throws {@link IllegalArgumentException} on out-of-range numeric values. */
  private static void validate(Builder b) {
    requirePositive("max-known-packs", b.maxKnownPacks);
    requireNonNegative("handshake-cache-ttl", b.handshakeCacheTtlSeconds);
    requirePositive("handshake-timeout-ms", b.moddedHandshakeTimeoutMs);
    requirePositive("write-buffer-high-watermark", b.writeBufferHighWatermark);
    requirePositive("write-buffer-low-watermark", b.writeBufferLowWatermark);
    if (b.writeBufferLowWatermark > b.writeBufferHighWatermark) {
      throw new IllegalArgumentException(
          "write-buffer-low-watermark (" + b.writeBufferLowWatermark
              + ") must be <= write-buffer-high-watermark (" + b.writeBufferHighWatermark + ")");
    }
    requireNonNegative("smart-compression-min-delta", b.smartCompressionMinSizeDelta);
    requirePositive("packet-queue-max-depth", b.packetQueueMaxDepth);
    requirePositive("connection-throttle-max-per-second", b.connectionThrottleMaxPerSecond);
    requireNonNegative("slow-connection-threshold-ms", b.slowConnectionThresholdMs);
    requireNonNegative("seamless-switch-settle-ms", b.seamlessSwitchSettleMs);
    if (b.seamlessSwitchSettleMs > 5000) {
      throw new IllegalArgumentException("conduit.toml: seamless-switch-settle-ms ("
          + b.seamlessSwitchSettleMs + ") must be <= 5000");
    }
    requirePositive("health-check-interval-ms", b.healthCheckIntervalMs);
    requirePositive("health-check-failure-threshold", b.healthCheckFailureThreshold);
    requirePositive("health-check-success-threshold", b.healthCheckSuccessThreshold);
    requirePrefix("connection-throttle-ipv4-prefix", b.connectionThrottleIpv4Prefix, 32);
    requirePrefix("connection-throttle-ipv6-prefix", b.connectionThrottleIpv6Prefix, 128);
    requireNonNegative("connection-throttle-log-interval-ms", b.connectionThrottleLogIntervalMs);
    requirePositive("motd-cache-ttl-ms", b.motdCacheTtlMs);
    requirePositive("graceful-shutdown-timeout-ms", b.gracefulShutdownTimeoutMs);
    requirePositive("bot-filter-timeout-ms", b.botFilterTimeoutMs);
    requirePositive("bot-filter-threshold", b.botFilterThreshold);
    requirePositive("tab-complete-cache-ttl-ms", b.tabCompleteCacheTtlMs);
    requirePositive("tab-complete-cache-max-entries", b.tabCompleteCacheMaxEntries);
    requirePositive("attack-mode-connection-throttle-max-per-second",
        b.attackModePolicy.throttleMaxPerSecond());
    requirePositive("attack-mode-bot-filter-threshold", b.attackModePolicy.botFilterThreshold());
    requirePositive("attack-mode-motd-cache-ttl-ms", b.attackModePolicy.motdCacheTtlMs());
    requirePositive("metrics.http-port", b.metricsHttpPort);
    if (b.metricsHttpPath == null || !b.metricsHttpPath.startsWith("/")) {
      throw new IllegalArgumentException("conduit.toml: metrics.http-path must start with '/'");
    }
    if (b.metricsPrometheusPath == null || !b.metricsPrometheusPath.startsWith("/")) {
      throw new IllegalArgumentException(
          "conduit.toml: metrics.prometheus-path must start with '/'");
    }
    if (b.metricsPrometheusPath.equals(b.metricsHttpPath)) {
      throw new IllegalArgumentException("conduit.toml: metrics.prometheus-path must differ from"
          + " metrics.http-path (both are '" + b.metricsHttpPath + "')");
    }
    if (b.commandForwardingEnabled) {
      String channel = b.commandForwardingChannel;
      int colon = channel == null ? -1 : channel.indexOf(':');
      if (colon <= 0 || colon == channel.length() - 1) {
        throw new IllegalArgumentException("conduit.toml: forwarding.channel must be of the form"
            + " 'namespace:path' — got '" + channel + "'");
      }
    }
    if (b.versionPolicy.getAllowed().isEmpty()
        && b.versionPolicy.getMinimum() != null && b.versionPolicy.getMaximum() != null
        && b.versionPolicy.getMinimum().greaterThan(b.versionPolicy.getMaximum())) {
      throw new IllegalArgumentException("conduit.toml: versions.minimum ("
          + b.versionPolicy.getMinimum().getVersionIntroducedIn() + ") must be <= versions.maximum ("
          + b.versionPolicy.getMaximum().getMostRecentSupportedVersion() + ")");
    }
    String action = b.channelGuardAction;
    if (action == null
        || !(action.equalsIgnoreCase("drop")
              || action.equalsIgnoreCase("kick")
              || action.equalsIgnoreCase("log"))) {
      throw new IllegalArgumentException("conduit.toml: channel-guard-action must be one of"
          + " 'drop', 'kick', 'log' — got '" + action + "'");
    }
  }

  private static void requirePositive(String key, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException("conduit.toml: " + key + " must be > 0, got " + value);
    }
  }

  private static void requireNonNegative(String key, int value) {
    if (value < 0) {
      throw new IllegalArgumentException("conduit.toml: " + key + " must be >= 0, got " + value);
    }
  }

  /** Requires a CIDR prefix length within the address family's range. */
  private static void requirePrefix(String key, int value, int max) {
    if (value < 1 || value > max) {
      throw new IllegalArgumentException(
          "conduit.toml: " + key + " must be between 1 and " + max + ", got " + value);
    }
  }

  /** Pushes config values into subsystems that cache them statically for hot-path performance. */
  private void applyLiveValues() {
    // JVM property still beats the config file — documented in conduit.toml
    if (System.getProperty("velocity.max-known-packs") == null) {
      KnownPacksPacket.setMaxKnownPacks(maxKnownPacks);
      logger.info("[Conduit] max-known-packs set to {}", maxKnownPacks);
    } else {
      logger.info("[Conduit] max-known-packs overridden by JVM property: {}",
          KnownPacksPacket.getMaxKnownPacks());
    }
  }

  private static void extractDefault(Path dest) {
    try (InputStream in = ConduitConfig.class.getResourceAsStream(
        "/com/velocitypowered/proxy/conduit/conduit.toml")) {
      if (in == null) {
        logger.error("[Conduit] Default conduit.toml not found in jar — using built-in defaults.");
        return;
      }
      Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
      logger.info("[Conduit] Generated default conduit.toml");
    } catch (IOException e) {
      logger.error("[Conduit] Failed to extract conduit.toml: {}", e.getMessage());
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

  // ── Server getters ────────────────────────────────────────────────────────

  /** Returns whether backend health checking is enabled. */
  public boolean isHealthCheckEnabled() {
    return healthCheckEnabled;
  }

  /** Returns the interval in milliseconds between backend health-check rounds. */
  public int getHealthCheckIntervalMs() {
    return healthCheckIntervalMs;
  }

  /** Returns consecutive failed pings before a backend is marked unhealthy. */
  public int getHealthCheckFailureThreshold() {
    return healthCheckFailureThreshold;
  }

  /** Returns consecutive successful pings before an unhealthy backend is marked healthy again. */
  public int getHealthCheckSuccessThreshold() {
    return healthCheckSuccessThreshold;
  }

  /** Returns the ordered list of preferred fallback server names. */
  public List<String> getFallbackServers() {
    return fallbackServers;
  }

  /** Returns whether MOTD response caching is enabled. */
  public boolean isMotdCacheEnabled() {
    return motdCacheEnabled;
  }

  /** Returns the time-to-live in milliseconds for cached MOTD responses. */
  public int getMotdCacheTtlMs() {
    return motdCacheTtlMs;
  }

  /** Returns whether the graceful-shutdown hook is enabled. */
  public boolean isGracefulShutdownEnabled() {
    return gracefulShutdownEnabled;
  }

  /** Returns the maximum time in milliseconds to wait for graceful-shutdown transfers. */
  public int getGracefulShutdownTimeoutMs() {
    return gracefulShutdownTimeoutMs;
  }

  /** Returns the disconnect message shown to players when no fallback is available on shutdown. */
  public String getGracefulShutdownMessage() {
    return gracefulShutdownMessage;
  }

  /** Returns whether the incomplete-handshake bot filter is enabled. */
  public boolean isBotFilterEnabled() {
    return botFilterEnabled;
  }

  /** Returns the handshake completion timeout in milliseconds used by the bot filter. */
  public int getBotFilterTimeoutMs() {
    return botFilterTimeoutMs;
  }

  /** Returns the incomplete-handshake count threshold above which an IP is blocked. */
  public int getBotFilterThreshold() {
    return botFilterThreshold;
  }

  // ── Connection-throttle grouping getters ──────────────────────────────────

  /** Returns the IPv4 prefix length used to group sources for throttling and bot filtering. */
  public int getConnectionThrottleIpv4Prefix() {
    return connectionThrottleIpv4Prefix;
  }

  /** Returns the IPv6 prefix length used to group sources for throttling and bot filtering. */
  public int getConnectionThrottleIpv6Prefix() {
    return connectionThrottleIpv6Prefix;
  }

  /** Returns the minimum gap between aggregated connection-throttle drop reports, in ms. */
  public int getConnectionThrottleLogIntervalMs() {
    return connectionThrottleLogIntervalMs;
  }

  // ── Tab-complete cache getters ────────────────────────────────────────────

  /** Returns whether tab-complete response caching is enabled. */
  public boolean isTabCompleteCacheEnabled() {
    return tabCompleteCacheEnabled;
  }

  /** Returns the TTL in milliseconds for cached tab-complete responses. */
  public int getTabCompleteCacheTtlMs() {
    return tabCompleteCacheTtlMs;
  }

  /** Returns the maximum number of cached tab-complete entries per server. */
  public int getTabCompleteCacheMaxEntries() {
    return tabCompleteCacheMaxEntries;
  }

  // ── Channel-guard getters ─────────────────────────────────────────────────

  /** Returns whether the plugin-message channel guard is enabled. */
  public boolean isChannelGuardEnabled() {
    return channelGuardEnabled;
  }

  /** Returns the named ChannelGuard preset. */
  public ChannelGuardPreset getChannelGuardPreset() {
    return channelGuardPreset;
  }

  /** Returns the action taken on a blocked channel: {@code drop}, {@code kick}, or {@code log}. */
  public String getChannelGuardAction() {
    return channelGuardAction;
  }

  /** Returns the list of channel-name patterns rejected by the channel guard. */
  public List<String> getChannelGuardBlockList() {
    return channelGuardBlockList;
  }

  /** Returns stricter live limits used by {@code /conduit attackmode on}. */
  public AttackModePolicy getAttackModePolicy() {
    return attackModePolicy;
  }

  // ── Routing getters ──────────────────────────────────────────────────────

  /** Returns per-backend mod compatibility rules. */
  public ModCompatibilityRules getModCompatibilityRules() {
    return modCompatibilityRules;
  }

  // ── Metrics getters ──────────────────────────────────────────────────────

  /** Returns whether the lightweight HTTP metrics endpoint is enabled. */
  public boolean isMetricsHttpEnabled() {
    return metricsHttpEnabled;
  }

  /** Returns the bind host for the lightweight HTTP metrics endpoint. */
  public String getMetricsHttpHost() {
    return metricsHttpHost;
  }

  /** Returns the bind port for the lightweight HTTP metrics endpoint. */
  public int getMetricsHttpPort() {
    return metricsHttpPort;
  }

  /** Returns the URL path for the lightweight HTTP metrics endpoint. */
  public String getMetricsHttpPath() {
    return metricsHttpPath;
  }

  /** Returns the path serving the Prometheus text exposition. */
  public String getMetricsPrometheusPath() {
    return metricsPrometheusPath;
  }

  /** Returns the bearer token required by the metrics endpoint, or {@code ""} when open. */
  public String getMetricsAuthToken() {
    return metricsAuthToken;
  }

  // ── Versions getters ──────────────────────────────────────────────────────

  /**
   * Returns the advertised/accepted client-version policy from the {@code [versions]} section.
   *
   * <p>Never {@code null}; {@link VersionPolicy#DISABLED} when no restriction is configured.
   */
  public VersionPolicy getVersionPolicy() {
    return versionPolicy;
  }

  // ── Maintenance getters ───────────────────────────────────────────────────

  /** Returns whether the maintenance-mode subsystem is enabled (registers its listeners). */
  public boolean isMaintenanceFeatureEnabled() {
    return maintenanceFeatureEnabled;
  }

  /** Returns whether maintenance mode should be active immediately on proxy start. */
  public boolean isMaintenanceActiveOnStart() {
    return maintenanceActiveOnStart;
  }

  /** Returns the MiniMessage kick message shown to denied players during maintenance. */
  public String getMaintenanceKickMessage() {
    return maintenanceKickMessage;
  }

  /** Returns the MiniMessage MOTD shown in the server-list ping during maintenance. */
  public String getMaintenanceMotd() {
    return maintenanceMotd;
  }

  /** Returns the list of usernames always permitted to connect during maintenance. */
  public List<String> getMaintenanceAllowlist() {
    return maintenanceAllowlist;
  }

  // ── Command getters ───────────────────────────────────────────────────────

  /** Returns whether the {@code /conduit} admin command is registered. */
  public boolean isAdminCommandsEnabled() {
    return adminCommandsEnabled;
  }

  /** Returns whether the {@code /modlist} command is registered. */
  public boolean isModListCommandEnabled() {
    return modListCommandEnabled;
  }

  // ── Forwarding getters ────────────────────────────────────────────────────

  /** Returns whether backend-to-proxy command forwarding is enabled. */
  public boolean isCommandForwardingEnabled() {
    return commandForwardingEnabled;
  }

  /** Returns the plugin-messaging channel used for command forwarding. */
  public String getCommandForwardingChannel() {
    return commandForwardingChannel;
  }

  /**
   * Returns whether player-context forwarded commands require the
   * {@code conduit.forward.execute} permission.
   */
  public boolean isCommandForwardingRequirePermission() {
    return commandForwardingRequirePermission;
  }

  /** Returns whether backend-supplied log lines for forwarded commands are echoed to the console. */
  public boolean isCommandForwardingLog() {
    return commandForwardingLog;
  }

  /** Returns the backends permitted to forward commands; empty means every backend. */
  public List<String> getCommandForwardingAllowedServers() {
    return commandForwardingAllowedServers;
  }

  /** Returns the root command words a backend may forward; empty means every command. */
  public List<String> getCommandForwardingAllowlist() {
    return commandForwardingAllowlist;
  }

  /** Returns the root command words that are always refused when forwarded. */
  public List<String> getCommandForwardingDenylist() {
    return commandForwardingDenylist;
  }

  // ── Update getters ────────────────────────────────────────────────────────

  /** Returns whether Conduit's update checker runs at all. */
  public boolean isUpdateCheckEnabled() {
    return updateCheckEnabled;
  }

  /** Returns whether a single update summary is logged to the console at startup. */
  public boolean isUpdateNotifyOnStartup() {
    return updateNotifyOnStartup;
  }

  /**
   * Returns whether players holding {@code conduit.update.notify} are told about a newer release
   * when they join.
   */
  public boolean isUpdateNotifyOnJoin() {
    return updateNotifyOnJoin;
  }

  /** Returns the {@code owner/repo} GitHub slug the update checker compares releases against. */
  public String getUpdateRepository() {
    return updateRepository;
  }

  /**
   * Returns whether pre-releases are considered upgrade targets. Pre-release builds always
   * consider newer pre-releases regardless of this flag.
   */
  public boolean isUpdateIncludePrereleases() {
    return updateIncludePrereleases;
  }

  /** Returns how long, in minutes, a computed update result is cached before a refresh. */
  public int getUpdateCacheMinutes() {
    return updateCacheMinutes;
  }

  // ── Spark getters ─────────────────────────────────────────────────────────

  /** Returns whether Conduit should extract the bundled spark plugin on startup. */
  public boolean isSparkBundleEnabled() {
    return sparkBundleEnabled;
  }

  // ── LuckPerms getters ─────────────────────────────────────────────────────

  /** Returns whether Conduit should extract the bundled LuckPerms plugin on startup. */
  public boolean isLuckPermsBundleEnabled() {
    return luckPermsBundleEnabled;
  }

  // ── Advanced getters ──────────────────────────────────────────────────────

  /**
   * Returns whether experimental seamless server switches are enabled for 1.20.2+ clients.
   *
   * <p>Default {@code false}. When disabled, Conduit uses the existing configuration-phase
   * server switch. Based on the seamless server switching patch by ohemilyy.
   */
  public boolean isSeamlessServerSwitches() {
    return seamlessServerSwitches;
  }

  /**
   * Returns the settle delay (in milliseconds) applied to a seamless server switch.
   *
   * <p>During this window the client connection is held quiet after the switch packets are sent, so
   * the destination backend has a moment to stream in the world and player position before the
   * player's own input is processed. This both softens the otherwise instant switch and prevents
   * the "stuck until reconnect" desync that happens when a player moves before the new server has
   * finished loading them in. A value of {@code 0} disables the delay. Default {@code 250}.
   */
  public int getSeamlessSwitchSettleMs() {
    return seamlessSwitchSettleMs;
  }

  /** Returns whether a teleport sound is played to the player on a seamless server switch. */
  public boolean isSeamlessSwitchSoundEnabled() {
    return seamlessSwitchSoundEnabled;
  }

  /**
   * Returns the sound key played on a seamless server switch (default
   * {@code minecraft:entity.enderman.teleport}, the ender pearl teleport sound).
   */
  public String getSeamlessSwitchSound() {
    return seamlessSwitchSound;
  }

  /** Returns the volume of the seamless switch sound. Default {@code 1.0}. */
  public float getSeamlessSwitchSoundVolume() {
    return seamlessSwitchSoundVolume;
  }

  /** Returns the pitch of the seamless switch sound. Default {@code 1.0}. */
  public float getSeamlessSwitchSoundPitch() {
    return seamlessSwitchSoundPitch;
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  /** Mutable builder used internally by {@link #fromToml} to construct a {@link ConduitConfig}. */
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
    int connectionThrottleIpv4Prefix = ConnectionThrottler.DEFAULT_IPV4_PREFIX;
    int connectionThrottleIpv6Prefix = ConnectionThrottler.DEFAULT_IPV6_PREFIX;
    int connectionThrottleLogIntervalMs = (int) ConnectionThrottler.DEFAULT_LOG_INTERVAL_MS;

    boolean diagnosticsEnabled = false;
    boolean traceModHandshakes = false;
    int slowConnectionThresholdMs = 3000;

    boolean healthCheckEnabled = true;
    int healthCheckIntervalMs = 10000;
    int healthCheckFailureThreshold = BackendHealthChecker.DEFAULT_FAILURE_THRESHOLD;
    int healthCheckSuccessThreshold = BackendHealthChecker.DEFAULT_SUCCESS_THRESHOLD;
    List<String> fallbackServers = Collections.emptyList();
    boolean motdCacheEnabled = true;
    int motdCacheTtlMs = 2000;
    boolean gracefulShutdownEnabled = true;
    int gracefulShutdownTimeoutMs = 5000;
    String gracefulShutdownMessage = "Proxy is restarting. Please reconnect in a moment.";
    boolean botFilterEnabled = true;
    int botFilterTimeoutMs = 3000;
    int botFilterThreshold = 10;

    boolean tabCompleteCacheEnabled = false;
    int tabCompleteCacheTtlMs = 1500;
    int tabCompleteCacheMaxEntries = 1024;

    boolean channelGuardEnabled = false;
    ChannelGuardPreset channelGuardPreset = ChannelGuardPreset.CUSTOM;
    String channelGuardAction = "drop";
    List<String> channelGuardBlockList = DEFAULT_CHANNEL_BLOCK_LIST;
    AttackModePolicy attackModePolicy = new AttackModePolicy(8, 3, 10000);

    ModCompatibilityRules modCompatibilityRules = ModCompatibilityRules.ALLOW_ALL;

    boolean metricsHttpEnabled = false;
    String metricsHttpHost = "127.0.0.1";
    int metricsHttpPort = 9589;
    String metricsHttpPath = "/metrics";
    String metricsPrometheusPath = "/metrics/prometheus";
    String metricsAuthToken = "";

    boolean maintenanceFeatureEnabled = true;
    boolean maintenanceActiveOnStart = false;
    String maintenanceKickMessage =
        "<red>The network is currently down for maintenance.\n<gray>Please check back soon.";
    String maintenanceMotd =
        "<red><bold>⚠ Maintenance</bold></red>\n<gray>The network is temporarily offline.";
    List<String> maintenanceAllowlist = Collections.emptyList();

    VersionPolicy versionPolicy = VersionPolicy.DISABLED;

    boolean adminCommandsEnabled = true;
    boolean modListCommandEnabled = true;

    boolean commandForwardingEnabled = false;
    String commandForwardingChannel = "velocity_command_forward:main";
    boolean commandForwardingRequirePermission = false;
    boolean commandForwardingLog = true;
    List<String> commandForwardingAllowedServers = Collections.emptyList();
    List<String> commandForwardingAllowlist = Collections.emptyList();
    List<String> commandForwardingDenylist = Collections.emptyList();

    boolean updateCheckEnabled = true;
    boolean updateNotifyOnStartup = true;
    boolean updateNotifyOnJoin = true;
    String updateRepository = "tame-gg/conduit";
    boolean updateIncludePrereleases = false;
    int updateCacheMinutes = 360;

    boolean sparkBundleEnabled = true;

    boolean luckPermsBundleEnabled = true;

    boolean seamlessServerSwitches = false;
    int seamlessSwitchSettleMs = 250;
    boolean seamlessSwitchSoundEnabled = true;
    String seamlessSwitchSound = "minecraft:entity.enderman.teleport";
    float seamlessSwitchSoundVolume = 1.0f;
    float seamlessSwitchSoundPitch = 1.0f;

    /**
     * Default channel blocklist for {@code ChannelGuard}: well-known World-Downloader and X-Ray
     * client channels.  Operators can extend this list via {@code conduit.toml}.
     */
    static final List<String> DEFAULT_CHANNEL_BLOCK_LIST = List.of(
        "wdl:init",
        "wdl:control",
        "wdl:request",
        "world_downloader:init",
        "world_downloader:control",
        "world_downloader:request",
        "xaero:",
        "schematica:",
        "bsm:",
        "5zig:");
  }
}
