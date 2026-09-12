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
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

  /**
   * Permission that exempts a player from drain redirection.
   *
   * <p>Draining exists so a backend can be restarted without disrupting players, and the person
   * doing the restarting is exactly the one who needs to be on it — to watch it come back, run a
   * command, or confirm it is really empty. Staff holding this node can still be sent to a draining
   * server, and are left alone when it is evacuated.
   */
  public static final String DRAIN_BYPASS_PERMISSION = "conduit.drain.bypass";

  /** Redirects allowed for one player inside {@link #REDIRECT_WINDOW_MS} before giving up. */
  private static final int MAX_REDIRECTS_PER_WINDOW = 3;

  /** Window over which {@link #MAX_REDIRECTS_PER_WINDOW} is counted. */
  private static final long REDIRECT_WINDOW_MS = 15_000L;

  /** Cap on tracked redirect budgets; stale entries are pruned past this. */
  private static final int MAX_TRACKED_PLAYERS = 1024;

  private static final Logger logger = LogManager.getLogger(FallbackRouter.class);

  /** A no-op router returned when fallback routing is disabled. */
  public static final FallbackRouter DISABLED = new FallbackRouter(
      new BackendHealthChecker(10_000L, 1, 1, false), List.of(), null) {
    @Override
    public void register(Object plugin, ProxyServer proxy) {
      // no-op
    }

    @Override
    public void onKickedFromServer(KickedFromServerEvent event) {
      // no-op
    }

    @Override
    public void onServerPreConnect(ServerPreConnectEvent event) {
      // no-op
    }

    @Override
    public void onServerConnected(ServerConnectedEvent event) {
      // no-op
    }

    @Override
    public void onDisconnect(DisconnectEvent event) {
      // no-op
    }

    @Override
    public Optional<RegisteredServer> simulateFallback(String kickedFrom) {
      return Optional.empty();
    }

    @Override
    public int evacuate(String serverName) {
      return 0;
    }
  };

  private final BackendHealthChecker healthChecker;
  private volatile List<String> configuredFallbacks;
  private final ProxyServer proxy;
  private final ConcurrentHashMap<UUID, RedirectBudget> redirectBudgets =
      new ConcurrentHashMap<>();

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
    this.configuredFallbacks = List.copyOf(configuredFallbacks);
    this.proxy = proxy;
  }

  /** Replaces the ordered preferred-fallback list, e.g. after {@code /conduit reload}. */
  public void setConfiguredFallbacks(List<String> configuredFallbacks) {
    this.configuredFallbacks = List.copyOf(configuredFallbacks);
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
   * Redirects a player who has just lost their server to a routable one from
   * {@code fallback-servers}.
   *
   * <h3>Which kicks this covers</h3>
   * {@link KickedFromServerEvent#kickedDuringServerConnect()} is {@code true} only when the player
   * was moving to <em>another</em> server and still holds their seat on the current one. Velocity's
   * default there is to keep them where they are and show a message, which is already the right
   * answer — so this handler ignores that case rather than yanking a settled player elsewhere.
   *
   * <p>The case worth acting on is the opposite one, {@code kickedDuringServerConnect() == false}:
   * the player's own server kicked them, went away under them, or — on a fresh login, when there is
   * no current server yet — their initial server could not be reached at all. Velocity fills in a
   * redirect from its own {@code velocity.toml try} retry deque and, once that is exhausted,
   * disconnects. This handler steps in when Velocity is about to disconnect, and when the server
   * Velocity picked is one this proxy knows is unhealthy or draining.
   *
   * @param event the kick event
   */
  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    if (event.kickedDuringServerConnect()) {
      // Still connected to their current server: leave Velocity's "notify and stay put" alone.
      return;
    }

    String kickedFrom = event.getServer().getServerInfo().getName();
    String username = event.getPlayer().getUsername();

    // Respect a redirect Velocity already chose, as long as it is somewhere we would send a player.
    if (event.getResult() instanceof KickedFromServerEvent.RedirectPlayer redirect
        && healthChecker.isRoutable(redirect.getServer())) {
      return;
    }

    if (!attemptBudget(event.getPlayer().getUniqueId())) {
      logger.warn("[Conduit] FallbackRouter: {} has been bounced {} times in {}s after losing"
          + " '{}'; letting the disconnect stand rather than looping.",
          username, MAX_REDIRECTS_PER_WINDOW, REDIRECT_WINDOW_MS / 1000, kickedFrom);
      return;
    }

    Optional<RegisteredServer> target = resolveTarget(kickedFrom);
    if (target.isPresent()) {
      event.setResult(KickedFromServerEvent.RedirectPlayer.create(target.get()));
      logger.info("[Conduit] FallbackRouter: rerouting {} (lost '{}') to '{}'.",
          username, kickedFrom, target.get().getServerInfo().getName());
    } else {
      logger.info("[Conduit] FallbackRouter: no routable fallback for {} (lost '{}');"
          + " player will be disconnected.", username, kickedFrom);
    }
  }

  /**
   * Rate-limits how often one player may be redirected, so two backends that are both refusing
   * connections cannot bounce somebody between them forever. Exceeding the budget lets Velocity's
   * own result — normally a disconnect carrying the real reason — stand.
   *
   * @return {@code true} when a redirect may be issued
   */
  private boolean attemptBudget(UUID player) {
    long now = System.currentTimeMillis();
    RedirectBudget budget = redirectBudgets.compute(player, (id, existing) ->
        existing == null || now - existing.windowStart() >= REDIRECT_WINDOW_MS
            ? new RedirectBudget(now, 1)
            : new RedirectBudget(existing.windowStart(), existing.attempts() + 1));
    if (redirectBudgets.size() > MAX_TRACKED_PLAYERS) {
      redirectBudgets.entrySet()
          .removeIf(entry -> now - entry.getValue().windowStart() >= REDIRECT_WINDOW_MS);
    }
    return budget.attempts() <= MAX_REDIRECTS_PER_WINDOW;
  }

  /** Forgets a player's redirect budget once they have landed somewhere. */
  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    redirectBudgets.remove(event.getPlayer().getUniqueId());
  }

  /** Forgets a player's redirect budget when they leave. */
  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    redirectBudgets.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Keeps new players away from a drained backend.
   *
   * <p>A drained server keeps the players it already has — draining is not kicking — but any
   * attempt to send someone new there is redirected to the same target the router would pick if the
   * server had failed. If nothing else is routable the connection is left alone: refusing it
   * outright would be worse than sending one more player to a server that is merely restarting
   * soon.
   *
   * <p>Players with {@link #DRAIN_BYPASS_PERMISSION} are sent where they asked to go.
   */
  @Subscribe
  public void onServerPreConnect(ServerPreConnectEvent event) {
    Optional<RegisteredServer> requested = event.getResult().getServer();
    if (requested.isEmpty()) {
      return;
    }
    String name = requested.get().getServerInfo().getName();
    if (!healthChecker.isDrained(name)) {
      return;
    }
    if (event.getPlayer().hasPermission(DRAIN_BYPASS_PERMISSION)) {
      logger.info("[Conduit] FallbackRouter: '{}' is draining, but {} holds '{}' — allowing.",
          name, event.getPlayer().getUsername(), DRAIN_BYPASS_PERMISSION);
      return;
    }
    Optional<RegisteredServer> target = resolveTarget(name);
    if (target.isEmpty()) {
      logger.warn("[Conduit] FallbackRouter: '{}' is draining but no other server is routable;"
          + " allowing {} through.", name, event.getPlayer().getUsername());
      return;
    }
    event.setResult(ServerPreConnectEvent.ServerResult.allowed(target.get()));
    logger.info("[Conduit] FallbackRouter: '{}' is draining — routing {} to '{}' instead.",
        name, event.getPlayer().getUsername(), target.get().getServerInfo().getName());
  }

  /** Returns the fallback target Conduit would choose if {@code kickedFrom} became unavailable. */
  public Optional<RegisteredServer> simulateFallback(String kickedFrom) {
    return resolveTarget(kickedFrom);
  }

  /**
   * Moves every player currently on {@code serverName} to a routable server, as the final step of
   * draining it for a restart.
   *
   * <p>Players holding {@link #DRAIN_BYPASS_PERMISSION} are left where they are.
   *
   * @return the number of players a move was started for
   */
  public int evacuate(String serverName) {
    int moved = 0;
    for (Player player : proxy.getAllPlayers()) {
      boolean here = player.getCurrentServer()
          .map(conn -> conn.getServerInfo().getName().equals(serverName))
          .orElse(false);
      if (!here || player.hasPermission(DRAIN_BYPASS_PERMISSION)) {
        continue;
      }
      Optional<RegisteredServer> target = resolveTarget(serverName);
      if (target.isEmpty()) {
        logger.warn("[Conduit] FallbackRouter: cannot evacuate {} from '{}' — nothing routable.",
            player.getUsername(), serverName);
        continue;
      }
      player.createConnectionRequest(target.get()).fireAndForget();
      player.sendMessage(Component.text(
          "This server is restarting shortly — moving you to "
              + target.get().getServerInfo().getName() + ".", NamedTextColor.YELLOW));
      moved++;
    }
    return moved;
  }

  private Optional<RegisteredServer> resolveTarget(String kickedFrom) {
    // 1. Try configured fallbacks in order.
    for (String name : configuredFallbacks) {
      if (name.equals(kickedFrom)) {
        continue;
      }
      Optional<? extends RegisteredServer> rs = proxy.getServer(name);
      if (rs.isPresent() && healthChecker.isRoutable(rs.get())) {
        return Optional.of(rs.get());
      }
    }
    // 2. Try any routable server that is not the kicked-from server.
    for (RegisteredServer server : proxy.getAllServers()) {
      if (server.getServerInfo().getName().equals(kickedFrom)) {
        continue;
      }
      if (healthChecker.isRoutable(server)) {
        return Optional.of(server);
      }
    }
    return Optional.empty();
  }

  /**
   * How many redirects a player has been given since {@code windowStart}.
   *
   * @param windowStart when the current counting window opened
   * @param attempts    redirects issued inside that window
   */
  private record RedirectBudget(long windowStart, int attempts) {
  }
}
