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

/** Immutable JSON-ready snapshot of Conduit's diagnostics counters. */
public record ConduitMetricsSnapshot(
    long totalConnections,
    long moddedConnections,
    long handshakeCacheHits,
    long handshakeCacheMisses,
    long throttledConnections,
    long oversizedPayloads,
    long slowLogins,
    long compressionSkips,
    long packetQueueFlushes,
    long tabCompleteCacheHits,
    long tabCompleteCacheMisses,
    long channelsBlocked) {

  /** Builds a snapshot from the live diagnostics registry. */
  public static ConduitMetricsSnapshot from(ConduitDiagnostics diagnostics) {
    return new ConduitMetricsSnapshot(
        diagnostics.getTotalConnections(),
        diagnostics.getModdedConnections(),
        diagnostics.getHandshakeCacheHits(),
        diagnostics.getHandshakeCacheMisses(),
        diagnostics.getThrottledConnections(),
        diagnostics.getOversizedPayloads(),
        diagnostics.getSlowLogins(),
        diagnostics.getCompressionSkips(),
        diagnostics.getPacketQueueFlushes(),
        diagnostics.getTabCompleteCacheHits(),
        diagnostics.getTabCompleteCacheMisses(),
        diagnostics.getChannelsBlocked());
  }

  /** Renders this snapshot as compact stable JSON for dashboards and simple scrapers. */
  public String toJson() {
    return "{"
        + "\"totalConnections\":" + totalConnections
        + ",\"moddedConnections\":" + moddedConnections
        + ",\"handshakeCacheHits\":" + handshakeCacheHits
        + ",\"handshakeCacheMisses\":" + handshakeCacheMisses
        + ",\"throttledConnections\":" + throttledConnections
        + ",\"oversizedPayloads\":" + oversizedPayloads
        + ",\"slowLogins\":" + slowLogins
        + ",\"compressionSkips\":" + compressionSkips
        + ",\"packetQueueFlushes\":" + packetQueueFlushes
        + ",\"tabCompleteCacheHits\":" + tabCompleteCacheHits
        + ",\"tabCompleteCacheMisses\":" + tabCompleteCacheMisses
        + ",\"channelsBlocked\":" + channelsBlocked
        + "}";
  }

  /**
   * Renders this snapshot in the Prometheus text exposition format (version 0.0.4).
   *
   * <p>Every counter is exported as a {@code counter} named {@code conduit_<name>_total}, which is
   * what these values are: monotonically increasing totals since proxy start. Scrapers derive rates
   * themselves, so no rate is computed here.
   */
  public String toPrometheus() {
    StringBuilder out = new StringBuilder(1024);
    counter(out, "connections_total", "Client connections accepted by the proxy.", totalConnections);
    counter(out, "modded_connections_total", "Connections identified as modded clients.",
        moddedConnections);
    counter(out, "handshake_cache_hits_total", "Modded handshake negotiations served from cache.",
        handshakeCacheHits);
    counter(out, "handshake_cache_misses_total", "Modded handshake negotiations not cached.",
        handshakeCacheMisses);
    counter(out, "throttled_connections_total",
        "Connections dropped by the per-source connection throttle.", throttledConnections);
    counter(out, "oversized_payloads_total", "Plugin-message payloads rejected as oversized.",
        oversizedPayloads);
    counter(out, "slow_logins_total", "Logins slower than the configured threshold.", slowLogins);
    counter(out, "compression_skips_total", "Packets the smart-compression path declined.",
        compressionSkips);
    counter(out, "packet_queue_flushes_total", "Queued play-packet batches flushed after a switch.",
        packetQueueFlushes);
    counter(out, "tab_complete_cache_hits_total", "Tab-complete responses served from cache.",
        tabCompleteCacheHits);
    counter(out, "tab_complete_cache_misses_total", "Tab-complete responses not cached.",
        tabCompleteCacheMisses);
    counter(out, "channels_blocked_total", "Plugin messages blocked by the channel guard.",
        channelsBlocked);
    return out.toString();
  }

  private static void counter(StringBuilder out, String name, String help, long value) {
    String metric = "conduit_" + name;
    out.append("# HELP ").append(metric).append(' ').append(help).append('\n')
        .append("# TYPE ").append(metric).append(" counter\n")
        .append(metric).append(' ').append(value).append('\n');
  }
}
