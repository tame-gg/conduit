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

package com.velocitypowered.proxy.conduit.modded;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Populates a {@link ModdedClientTracker} from public Velocity events.
 *
 * <p>Players advertise their plugin-message channels via the vanilla
 * {@code minecraft:register} channel (or its legacy unnamespaced {@code REGISTER} form).
 * Each REGISTER payload is a NUL-delimited UTF-8 list of channel names. This listener captures
 * those payloads and feeds them into {@link ModdedClientTracker#inferModType} so the tracker
 * always reflects the latest channel set.
 *
 * <p>{@link DisconnectEvent} is used to evict tracker entries so memory does not grow with stale
 * player records.
 *
 * <p>Using public events (rather than overlaying a Velocity session handler) keeps this listener
 * upstream-merge-safe and decoupled from internal packet plumbing. The trade-off is that we only
 * see channels the client explicitly REGISTERs; channels established via a custom login-phase
 * handshake (e.g., NeoForge's wrapped login) are not visible here. Those flows already have their
 * own paths through {@code NeoForgeHandshakeUtil}, which can update the tracker directly.
 */
public final class ModTrackerListener {

  private static final Logger logger = LogManager.getLogger(ModTrackerListener.class);
  private static final String NAMESPACED_REGISTER = "minecraft:register";
  private static final String LEGACY_REGISTER = "REGISTER";

  private final ModdedClientTracker tracker;
  /** Channels accumulated per-player across multiple REGISTER messages. */
  private final ConcurrentHashMap<UUID, List<String>> pendingChannels = new ConcurrentHashMap<>();

  /** Constructs a listener that writes into the given tracker. */
  public ModTrackerListener(ModdedClientTracker tracker) {
    this.tracker = tracker;
  }

  /** Registers this listener with the given proxy. */
  public void register(Object plugin, ProxyServer proxy) {
    proxy.getEventManager().register(plugin, this);
    logger.info("[Conduit] ModTrackerListener registered.");
  }

  /**
   * Captures REGISTER messages and updates the tracker for the originating player.
   *
   * <p>Runs at {@link PostOrder#LATE} so plugins that mutate the event have already run by the
   * time we inspect the channel list.
   */
  @Subscribe(order = PostOrder.LATE)
  public void onPluginMessage(PluginMessageEvent event) {
    if (!(event.getSource() instanceof Player player)) {
      return;
    }
    String channelId = event.getIdentifier().getId();
    if (!NAMESPACED_REGISTER.equalsIgnoreCase(channelId)
        && !LEGACY_REGISTER.equalsIgnoreCase(channelId)) {
      return;
    }
    String[] decoded;
    try {
      decoded = NeoForgeHandshakeUtil.decodeChannelList(event.getData());
    } catch (RuntimeException ex) {
      logger.debug("[Conduit] ModTrackerListener: failed to decode REGISTER from {}: {}",
          player.getUsername(), ex.getMessage());
      return;
    }
    if (decoded.length == 0) {
      return;
    }
    UUID id = player.getUniqueId();
    List<String> merged = pendingChannels.compute(id, (k, existing) -> {
      List<String> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
      for (String ch : decoded) {
        if (!ch.isEmpty() && !next.contains(ch)) {
          next.add(ch);
        }
      }
      return next;
    });
    List<String> snapshot = Collections.unmodifiableList(new ArrayList<>(merged));
    ModdedClientTracker.ClientModType type =
        ModdedClientTracker.inferModType(snapshot, false);
    // Known-pack namespaces are sourced from the configuration phase (KnownPacksPacket), not from
    // REGISTER messages. We pass an empty list here; a future overlay can update them separately.
    tracker.record(id, type, snapshot, List.of());
  }

  /** Cleans up tracker state when a player disconnects. */
  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    pendingChannels.remove(id);
    tracker.remove(id);
  }

  /** Returns the number of players for which we have at least one REGISTER payload. */
  public int pendingCount() {
    return pendingChannels.size();
  }
}
