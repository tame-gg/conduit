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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;
import com.velocitypowered.api.event.player.KickedFromServerEvent.Notify;
import com.velocitypowered.api.event.player.KickedFromServerEvent.RedirectPlayer;
import com.velocitypowered.api.event.player.KickedFromServerEvent.ServerKickResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class FallbackRouterTest {

  private final Map<String, RegisteredServer> servers = new LinkedHashMap<>();

  private RegisteredServer server(String name) {
    return servers.computeIfAbsent(name, key -> {
      RegisteredServer registered = mock(RegisteredServer.class);
      when(registered.getServerInfo())
          .thenReturn(new ServerInfo(key, new InetSocketAddress("127.0.0.1", 25566)));
      return registered;
    });
  }

  private ProxyServer proxy() {
    ProxyServer proxy = mock(ProxyServer.class);
    when(proxy.getAllServers()).thenAnswer(invocation -> List.copyOf(servers.values()));
    when(proxy.getServer(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(servers.get(invocation.getArgument(0))));
    return proxy;
  }

  private Player player() {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getUsername()).thenReturn("notazandi");
    return player;
  }

  /** Builds a kick event with a mutable result, as Velocity hands it to listeners. */
  private KickedFromServerEvent kick(Player player, String from, boolean duringServerConnect,
      ServerKickResult initial) {
    return new KickedFromServerEvent(player, server(from), Component.text("boom"),
        duringServerConnect, initial);
  }

  @Test
  void redirectsWhenVelocityWouldDisconnect() {
    server("lobby");
    server("hardcore");
    BackendHealthChecker health = new BackendHealthChecker(10_000L, 3, 2, true);
    FallbackRouter router = new FallbackRouter(health, List.of("hardcore"), proxy());

    KickedFromServerEvent event = kick(player(), "lobby", false,
        DisconnectPlayer.create(Component.text("bye")));
    router.onKickedFromServer(event);

    RedirectPlayer result = assertInstanceOf(RedirectPlayer.class, event.getResult());
    assertEquals("hardcore", result.getServer().getServerInfo().getName());
  }

  /**
   * The initial-login case: there is no current server yet, the configured one could not be
   * reached, and Velocity's own retry list is exhausted. This is the case operators expect
   * {@code fallback-servers} to cover.
   */
  @Test
  void redirectsWhenTheInitialConnectionFails() {
    server("main");
    server("hardcore");
    BackendHealthChecker health = new BackendHealthChecker(10_000L, 3, 2, true);
    FallbackRouter router = new FallbackRouter(health, List.of("hardcore"), proxy());

    KickedFromServerEvent event = kick(player(), "main", false,
        DisconnectPlayer.create(Component.text("cant-connect")));
    router.onKickedFromServer(event);

    RedirectPlayer result = assertInstanceOf(RedirectPlayer.class, event.getResult());
    assertEquals("hardcore", result.getServer().getServerInfo().getName());
  }

  /** A player who still holds a seat on their current server must be left where they are. */
  @Test
  void leavesPlayersWhoStillHaveTheirServerAlone() {
    server("lobby");
    server("hardcore");
    BackendHealthChecker health = new BackendHealthChecker(10_000L, 3, 2, true);
    FallbackRouter router = new FallbackRouter(health, List.of("hardcore"), proxy());

    ServerKickResult notify = Notify.create(Component.text("that server is down"));
    KickedFromServerEvent event = kick(player(), "minigames", true, notify);
    router.onKickedFromServer(event);

    assertSame(notify, event.getResult());
  }

  @Test
  void respectsTheRedirectVelocityAlreadyChose() {
    server("lobby");
    server("hardcore");
    BackendHealthChecker health = new BackendHealthChecker(10_000L, 3, 2, true);
    FallbackRouter router = new FallbackRouter(health, List.of("hardcore"), proxy());

    ServerKickResult chosen = RedirectPlayer.create(server("lobby"));
    KickedFromServerEvent event = kick(player(), "minigames", false, chosen);
    router.onKickedFromServer(event);

    assertSame(chosen, event.getResult());
  }

  @Test
  void overridesRedirectsToDrainingServers() {
    server("lobby");
    server("hardcore");
    BackendHealthChecker health = new BackendHealthChecker(10_000L, 3, 2, true);
    health.setDrained("lobby", true);
    FallbackRouter router = new FallbackRouter(health, List.of("hardcore"), proxy());

    KickedFromServerEvent event = kick(player(), "minigames", false,
        RedirectPlayer.create(server("lobby")));
    router.onKickedFromServer(event);

    RedirectPlayer result = assertInstanceOf(RedirectPlayer.class, event.getResult());
    assertEquals("hardcore", result.getServer().getServerInfo().getName());
  }

  /** Two backends that both refuse connections must not bounce a player between them forever. */
  @Test
  void stopsRedirectingAfterRepeatedFailures() {
    server("lobby");
    server("hardcore");
    BackendHealthChecker health = new BackendHealthChecker(10_000L, 3, 2, true);
    FallbackRouter router = new FallbackRouter(health, List.of("hardcore"), proxy());
    Player player = player();

    for (int i = 0; i < 3; i++) {
      KickedFromServerEvent event = kick(player, "lobby", false,
          DisconnectPlayer.create(Component.text("bye")));
      router.onKickedFromServer(event);
      assertInstanceOf(RedirectPlayer.class, event.getResult(), "attempt " + (i + 1));
    }

    ServerKickResult disconnect = DisconnectPlayer.create(Component.text("bye"));
    KickedFromServerEvent fourth = kick(player, "lobby", false, disconnect);
    router.onKickedFromServer(fourth);

    assertSame(disconnect, fourth.getResult(), "the fourth kick in the window must not redirect");
  }

  @Test
  void disconnectStandsWhenNothingIsRoutable() {
    server("lobby");
    BackendHealthChecker health = new BackendHealthChecker(10_000L, 3, 2, true);
    health.setDrained("lobby", true);
    FallbackRouter router = new FallbackRouter(health, List.of(), proxy());

    ServerKickResult disconnect = DisconnectPlayer.create(Component.text("bye"));
    KickedFromServerEvent event = kick(player(), "lobby", false, disconnect);
    router.onKickedFromServer(event);

    assertSame(disconnect, event.getResult());
  }
}
