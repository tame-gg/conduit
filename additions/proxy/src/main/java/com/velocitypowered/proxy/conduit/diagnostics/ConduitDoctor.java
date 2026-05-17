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

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.conduit.Conduit;
import com.velocitypowered.proxy.conduit.ConduitConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an operator-facing configuration and wiring report for {@code /conduit doctor}.
 */
public final class ConduitDoctor {

  private ConduitDoctor() {}

  /**
   * Returns a concise health report for Conduit-specific configuration.
   *
   * @param conduit the active Conduit instance
   * @param proxy the active Velocity proxy
   * @return multi-line report suitable for chat or console output
   */
  public static String buildReport(Conduit conduit, ProxyServer proxy) {
    ConduitConfig cfg = conduit.getConfig();
    List<String> warnings = new ArrayList<>();

    if (cfg.getMaxKnownPacks() < ConduitConfig.DEFAULT_MAX_KNOWN_PACKS) {
      warnings.add("max-known-packs is below the Conduit default; large modpacks may disconnect");
    }
    if (cfg.getWriteBufferLowWatermark() > cfg.getWriteBufferHighWatermark()) {
      warnings.add("write-buffer-low-watermark is greater than write-buffer-high-watermark");
    }
    for (String fallback : cfg.getFallbackServers()) {
      if (proxy.getServer(fallback).isEmpty()) {
        warnings.add("fallback server '" + fallback + "' is not registered in velocity.toml");
      }
    }
    if (cfg.isTabCompleteCacheEnabled()) {
      warnings.add("tab-complete-cache is enabled, but the play-session overlay is not wired yet");
    }
    if (cfg.isSmartCompressionEnabled()) {
      warnings.add("smart-compression is configured, but the compression overlay is not wired yet");
    }
    if (cfg.isPacketQueueOptEnabled()) {
      warnings.add("packet-queue-optimization is configured, but the transition overlay is not "
          + "wired yet");
    }

    StringBuilder report = new StringBuilder();
    report.append("Conduit Doctor v").append(conduit.getConduitVersion()).append('\n');
    report.append("  Known-packs limit      : ").append(cfg.getMaxKnownPacks()).append('\n');
    report.append("  Connection throttle    : ").append(cfg.isConnectionThrottleEnabled())
        .append(" (").append(cfg.getConnectionThrottleMaxPerSecond()).append("/s)\n");
    report.append("  Bot filter             : ").append(cfg.isBotFilterEnabled())
        .append(" (timeout ").append(cfg.getBotFilterTimeoutMs())
        .append("ms, threshold ").append(cfg.getBotFilterThreshold()).append(")\n");
    report.append("  Backend health checks  : ").append(cfg.isHealthCheckEnabled()).append('\n');
    report.append("  Fallback servers       : ").append(cfg.getFallbackServers()).append('\n');
    report.append("  Channel guard          : ").append(cfg.isChannelGuardEnabled())
        .append(" (").append(cfg.getChannelGuardAction()).append(")\n");

    if (warnings.isEmpty()) {
      report.append("  Result                 : OK");
    } else {
      report.append("  Warnings               : ").append(warnings.size()).append('\n');
      for (String warning : warnings) {
        report.append("   - ").append(warning).append('\n');
      }
    }
    return report.toString();
  }
}
