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
}
