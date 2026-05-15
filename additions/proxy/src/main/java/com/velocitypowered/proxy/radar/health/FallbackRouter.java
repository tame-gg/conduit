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

package com.velocitypowered.proxy.radar.health;

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
 * <p>If no suitable server can be found, the event result is left unchanged so Velocity applies
 * its default behaviour (disconnect).
 */
public final class FallbackRouter {

  private static final Logger logger = LogManager.getLogger(FallbackRouter.class);

  private final BackendHealthChecker healthChecker;
  private final List<String> configuredFallbacks;

  /**
   * Constructs a {@code FallbackRouter} backed by the given health checker and preferred fallback
   * server list.
   *
   * @param healthChecker       the health checker used to filter out unhealthy servers
   * @param configuredFallbacks ordered list of preferred fallback server names from the config
   */
  public FallbackRouter(BackendHealthChecker healthChecker, List<String> configuredFallbacks) {
    this.healthChecker = healthChecker;
    this.configuredFallbacks = configuredFallbacks;
  }

  /**
   * Registers this router as an event listener on the given proxy.
   *
   * @param proxy the proxy server whose event manager will receive registrations
   */
  public void register(ProxyServer proxy) {
    proxy.getEventManager().register(this, this);
    logger.info("[Conduit] FallbackRouter registered ({} configured fallbacks).",
        configuredFallbacks.size());
  }

  /**
   * Handles a {@link KickedFromServerEvent} by attempting to find a healthy fallback server.
   *
   * @param event the kick event
   */
  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    if (!event.kickedDuringServerConnect()) {
      // Voluntary disconnects do not need rerouting.
      return;
    }

    String kickedFrom = event.getServer().getServerInfo().getName();
    ProxyServer proxy = event.getPlayer().getCurrentServer()
        .map(cs -> null) // can't get proxy from player; use field lookup below
        .orElse(null);

    // Resolve fallback using the proxy reference stored on the player's connection.
    Optional<RegisteredServer> target = findFallback(event, kickedFrom);
    if (target.isPresent()) {
      event.setResult(KickedFromServerEvent.RedirectPlayer.create(target.get()));
      logger.info("[Conduit] FallbackRouter: rerouting {} (kicked from '{}') to '{}'.",
          event.getPlayer().getUsername(),
          kickedFrom,
          target.get().getServerInfo().getName());
    } else {
      logger.info("[Conduit] FallbackRouter: no healthy fallback available for {} (kicked from"
          + " '{}'); player will be disconnected.",
          event.getPlayer().getUsername(), kickedFrom);
    }
  }

  private Optional<RegisteredServer> findFallback(KickedFromServerEvent event, String kickedFrom) {
    // We need the registered server list; access via the current server or event server's proxy.
    // Since ProxyServer is not directly available here, we use the registered server list
    // resolved through the event's server instance (VelocityServer implements ProxyServer).
    // The event.getServer() is a RegisteredServer; we cannot easily get the proxy from it.
    // We delegate resolution to a helper that accepts the event player's proxy handle.
    // In practice, Velocity calls listeners with full context available via injected fields or
    // the singleton. We rely on the overloaded variant below that takes the proxy explicitly.
    return Optional.empty();
  }

  /**
   * Handles a {@link KickedFromServerEvent} with explicit access to the proxy server.
   * This variant is preferred when the router is constructed with the proxy pre-bound.
   */
  static final class Bound extends FallbackRouter {

    private final ProxyServer proxy;

    /**
     * Constructs a proxy-bound {@code FallbackRouter}.
     *
     * @param healthChecker       the health checker to consult
     * @param configuredFallbacks ordered list of preferred fallback server names
     * @param proxy               the proxy server used to enumerate registered servers
     */
    Bound(BackendHealthChecker healthChecker, List<String> configuredFallbacks,
        ProxyServer proxy) {
      super(healthChecker, configuredFallbacks);
      this.proxy = proxy;
    }

    @Override
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
        Optional<RegisteredServer> rs = proxy.getServer(name);
        if (rs.isPresent() && healthChecker.isHealthy(rs.get())) {
          return rs;
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

  /**
   * Creates a {@code FallbackRouter} that is pre-bound to the given proxy server.
   *
   * @param healthChecker       the health checker to consult
   * @param configuredFallbacks ordered list of preferred fallback server names
   * @param proxy               the proxy server
   * @return a new proxy-bound router instance
   */
  public static FallbackRouter create(BackendHealthChecker healthChecker,
      List<String> configuredFallbacks, ProxyServer proxy) {
    return new Bound(healthChecker, configuredFallbacks, proxy);
  }
}
