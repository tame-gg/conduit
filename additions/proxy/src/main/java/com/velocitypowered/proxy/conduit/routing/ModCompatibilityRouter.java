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

package com.velocitypowered.proxy.conduit.routing;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker.ClientModType;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker.PlayerModState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Enforces per-backend client mod-loader compatibility rules. */
public final class ModCompatibilityRouter {

  private static final Logger logger = LogManager.getLogger(ModCompatibilityRouter.class);

  private final ModdedClientTracker tracker;
  private final ModCompatibilityRules rules;

  public ModCompatibilityRouter(ModdedClientTracker tracker, ModCompatibilityRules rules) {
    this.tracker = tracker;
    this.rules = rules;
  }

  /** Registers this router when any compatibility rules are active. */
  public void register(Object plugin, ProxyServer proxy) {
    if (!rules.isEnabled()) {
      return;
    }
    proxy.getEventManager().register(plugin, this);
    logger.info("[Conduit] ModCompatibilityRouter registered ({} server rules).",
        rules.asMap().size());
  }

  @Subscribe(order = PostOrder.EARLY)
  public void onServerPreConnect(ServerPreConnectEvent event) {
    String serverName = event.getOriginalServer().getServerInfo().getName();
    PlayerModState state = tracker.getState(event.getPlayer().getUniqueId());
    ClientModType modType = state == null ? ClientModType.VANILLA : state.modType();
    if (rules.isAllowed(serverName, modType)) {
      return;
    }
    event.setResult(ServerPreConnectEvent.ServerResult.denied());
    event.getPlayer().sendMessage(Component.text(
        "Your client mod loader is not allowed on " + serverName + ".", NamedTextColor.RED));
    logger.info("[Conduit] Denied {} ({}) from connecting to incompatible backend '{}'.",
        event.getPlayer().getUsername(), modType, serverName);
  }
}
