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

package com.velocitypowered.proxy.conduit.health;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Listens for {@link KickedFromServerEvent} and attempts to redirect the player to a healthy
 * fallback server rather than disconnecting them.
 *
 * <p>Server selection order:
 * <ol>
 *   <li>Servers listed in {@code fallback-servers} in the config (in order), filtered to healthy
 *       ones that differ from the server the player was kicked from.</li>
 *   <li>Any other healthy server registered with the proxy, excluding the kicked-from server.</li>
 * </ol>
 *
 * <p>If no suitable server can be found the event result is left unchanged so Velocity applies
 * its default behaviour (disconnect).
 */
public class FallbackRouter {

  private static final Logger logger = LogManager.getLogger(FallbackRouter.class);

  /** A no-op router returned when fallback routing is disabled. */
  public static final FallbackRouter DISABLED = new FallbackRouter(
      BackendHealthChecker.DISABLED, List.of(), null) {
    @Override
    public void register(Object plugin, ProxyServer proxy) {
      // no-op
    }

    @Override
    public void onKickedFromServer(KickedFromServerEvent event) {
      // no-op
    }
  };

  private final BackendHealthChecker healthChecker;
  private final List<String> configuredFallbacks;
  private final ProxyServer proxy;

  /**
   * Constructs a {@code FallbackRouter}.
   *
   * @param healthChecker       the health checker used to filter out unhealthy servers
   * @param configuredFallbacks ordered list of preferred fallback server names from the config
   * @param proxy               the proxy server used to enumerate registered servers
   */
  public FallbackRouter(BackendHealthChecker healthChecker, List<String> configuredFallbacks,
      ProxyServer proxy) {
    this.healthChecker = healthChecker;
    this.configuredFallbacks = configuredFallbacks;
    this.proxy = proxy;
  }

  /**
   * Registers this router as an event listener on the proxy.
   *
   * @param plugin the owning plugin instance used for event registration
   * @param proxy  the proxy server whose event manager will receive this listener
   */
  public void register(Object plugin, ProxyServer proxy) {
    proxy.getEventManager().register(plugin, this);
    logger.info("[Conduit] FallbackRouter registered ({} configured fallbacks).",
        configuredFallbacks.size());
  }

  /**
   * Handles a {@link KickedFromServerEvent} by redirecting the player to a healthy fallback server.
   *
   * @param event the kick event
   */
  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    if (!event.kickedDuringServerConnect()) {
      return;
    }

    String kickedFrom = event.getServer().getServerInfo().getName();
    Optional<RegisteredServer> target = resolveTarget(kickedFrom);
    if (target.isPresent()) {
      event.setResult(KickedFromServerEvent.RedirectPlayer.create(target.get()));
      logger.info("[Conduit] FallbackRouter: rerouting {} (kicked from '{}') to '{}'.",
          event.getPlayer().getUsername(),
          kickedFrom,
          target.get().getServerInfo().getName());
    } else {
      logger.info("[Conduit] FallbackRouter: no healthy fallback for {} (kicked from '{}');"
          + " player will be disconnected.",
          event.getPlayer().getUsername(), kickedFrom);
    }
  }

  private Optional<RegisteredServer> resolveTarget(String kickedFrom) {
    // 1. Try configured fallbacks in order.
    for (String name : configuredFallbacks) {
      if (name.equals(kickedFrom)) {
        continue;
      }
      Optional<? extends RegisteredServer> rs = proxy.getServer(name);
      if (rs.isPresent() && healthChecker.isHealthy(rs.get())) {
        return Optional.of(rs.get());
      }
    }
    // 2. Try any healthy server that is not the kicked-from server.
    for (RegisteredServer server : proxy.getAllServers()) {
      if (server.getServerInfo().getName().equals(kickedFrom)) {
        continue;
      }
      if (healthChecker.isHealthy(server)) {
        return Optional.of(server);
      }
    }
    return Optional.empty();
  }
}
