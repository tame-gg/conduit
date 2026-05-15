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

package com.velocitypowered.proxy.radar.modded;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tracks the mod type and negotiated state for each connected player.
 *
 * <p>This information is used to make smarter decisions about packet forwarding, server transfers,
 * and handshake caching.  The map is keyed by player UUID and is cleared on disconnect.
 */
public final class ModdedClientTracker {

  /** Identifies the mod loader (or lack thereof) a client is using. */
  public enum ClientModType {
    VANILLA,
    FABRIC,
    LEGACY_FORGE,    // FML1 / FML2 (Minecraft ≤ 1.20.1 with Forge)
    NEOFORGE,        // FML3 (NeoForge / Forge ≥ 1.20.2)
    UNKNOWN_MODDED   // has mod channels but didn't match a known loader
  }

  /** Immutable snapshot of a player's detected mod state after handshake completion. */
  public record PlayerModState(
      UUID playerId,
      ClientModType modType,
      List<String> registeredChannels,
      List<String> knownPackNamespaces,
      long detectedAtMs
  ) {}

  private final ConcurrentHashMap<UUID, PlayerModState> states = new ConcurrentHashMap<>();

  /** Records a player's detected mod type and channel list after handshake completion. */
  public void record(UUID playerId, ClientModType modType,
      List<String> channels, List<String> knownPackNamespaces) {
    states.put(playerId, new PlayerModState(
        playerId, modType,
        Collections.unmodifiableList(channels),
        Collections.unmodifiableList(knownPackNamespaces),
        System.currentTimeMillis()));
  }

  /** Returns the recorded state for a player, or {@code null} if not yet tracked. */
  public @Nullable PlayerModState getState(UUID playerId) {
    return states.get(playerId);
  }

  /** Returns true if the player is known to be running any mod loader. */
  public boolean isModded(UUID playerId) {
    PlayerModState s = states.get(playerId);
    return s != null && s.modType() != ClientModType.VANILLA;
  }

  /** Removes tracking state when a player disconnects. */
  public void remove(UUID playerId) {
    states.remove(playerId);
  }

  /** Returns the number of currently tracked players. */
  public int trackedCount() {
    return states.size();
  }

  /** Returns a snapshot of all mod types currently connected. */
  public Set<ClientModType> connectedModTypes() {
    Set<ClientModType> types = EnumSet.noneOf(ClientModType.class);
    for (PlayerModState s : states.values()) {
      types.add(s.modType());
    }
    return types;
  }

  /**
   * Infers the mod type from a client's registered channel list.
   * Called after REGISTER packets are processed.
   */
  public static ClientModType inferModType(Iterable<String> channels, boolean hadFmlAddress) {
    boolean hasNeoForge = false;
    boolean hasFml = false;
    boolean hasModChannels = false;

    for (String ch : channels) {
      if (ch.startsWith("neoforge:")) {
        hasNeoForge = true;
      } else if (ch.startsWith("fml:")) {
        hasFml = true;
      } else if (ch.contains(":")) {
        hasModChannels = true;
      }
    }

    if (hasNeoForge) {
      return ClientModType.NEOFORGE;
    }
    if (hasFml || hadFmlAddress) {
      return ClientModType.LEGACY_FORGE;
    }
    if (hasModChannels) {
      return ClientModType.UNKNOWN_MODDED;
    }
    return ClientModType.VANILLA;
  }
}
