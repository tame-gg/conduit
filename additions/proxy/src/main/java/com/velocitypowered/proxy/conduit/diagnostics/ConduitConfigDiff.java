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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Human-readable diff between two Conduit config snapshots. */
public final class ConduitConfigDiff {

  private final List<Entry> entries = new ArrayList<>();

  private ConduitConfigDiff() {}

  /** Builds a diff from an active config snapshot to a candidate config snapshot. */
  public static ConduitConfigDiff between(ConduitConfig before, ConduitConfig after) {
    ConduitConfigDiff diff = new ConduitConfigDiff();
    diff.add("max-known-packs", before.getMaxKnownPacks(), after.getMaxKnownPacks(), true);
    diff.add("handshake-cache-ttl", before.getHandshakeCacheTtlSeconds(),
        after.getHandshakeCacheTtlSeconds(), true);
    diff.add("connection-throttle-max-per-second",
        before.getConnectionThrottleMaxPerSecond(), after.getConnectionThrottleMaxPerSecond(),
        true);
    diff.add("diagnostics.enabled", before.isDiagnosticsEnabled(), after.isDiagnosticsEnabled(),
        true);
    diff.add("diagnostics.trace-mod-handshakes", before.isTraceModHandshakes(),
        after.isTraceModHandshakes(), true);
    diff.add("write-buffer-high-watermark", before.getWriteBufferHighWatermark(),
        after.getWriteBufferHighWatermark(), false);
    diff.add("write-buffer-low-watermark", before.getWriteBufferLowWatermark(),
        after.getWriteBufferLowWatermark(), false);
    diff.add("health-check-interval-ms", before.getHealthCheckIntervalMs(),
        after.getHealthCheckIntervalMs(), false);
    diff.add("motd-cache-ttl-ms", before.getMotdCacheTtlMs(), after.getMotdCacheTtlMs(), false);
    diff.add("bot-filter-threshold", before.getBotFilterThreshold(), after.getBotFilterThreshold(),
        false);
    diff.add("channel-guard", before.isChannelGuardEnabled(), after.isChannelGuardEnabled(),
        false);
    diff.add("routing.mod-compatibility", before.getModCompatibilityRules(),
        after.getModCompatibilityRules(), false);
    return diff;
  }

  private void add(String key, Object before, Object after, boolean live) {
    if (!Objects.equals(before, after)) {
      entries.add(new Entry(key, String.valueOf(before), String.valueOf(after), live));
    }
  }

  /** Returns {@code true} when the snapshots are identical for tracked keys. */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** Renders a compact operator-facing diff. */
  public String toHumanString() {
    if (entries.isEmpty()) {
      return "No Conduit config changes detected.";
    }
    StringBuilder out = new StringBuilder("Conduit config changes:\n");
    for (Entry entry : entries) {
      out.append("  ")
          .append(entry.key())
          .append(": ")
          .append(entry.before())
          .append(" -> ")
          .append(entry.after())
          .append(" (")
          .append(entry.live() ? "live" : "restart")
          .append(")\n");
    }
    return out.toString();
  }

  private record Entry(String key, String before, String after, boolean live) {}
}
