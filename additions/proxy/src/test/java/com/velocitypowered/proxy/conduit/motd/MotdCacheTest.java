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

package com.velocitypowered.proxy.conduit.motd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MotdCacheTest {

  private static ProxyPingEvent ping(String address, ProtocolVersion version, String virtualHost,
      ServerPing response) throws Exception {
    InboundConnection connection = mock(InboundConnection.class);
    when(connection.getRemoteAddress())
        .thenReturn(new InetSocketAddress(InetAddress.getByName(address), 40000));
    when(connection.getProtocolVersion()).thenReturn(version);
    when(connection.getVirtualHost()).thenReturn(virtualHost == null
        ? Optional.empty()
        : Optional.of(InetSocketAddress.createUnresolved(virtualHost, 25565)));

    ProxyPingEvent event = mock(ProxyPingEvent.class);
    when(event.getConnection()).thenReturn(connection);
    when(event.getPing()).thenReturn(response);
    return event;
  }

  @Test
  void repeatedPingsFromOneClientAreServedFromCache() throws Exception {
    MotdCache cache = new MotdCache(60_000L);
    ServerPing response = mock(ServerPing.class);

    cache.onProxyPing(ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, "mc.example.com",
        response));
    ProxyPingEvent second = ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, "mc.example.com",
        mock(ServerPing.class));
    cache.onProxyPing(second);

    verify(second).setPing(response);
    assertEquals(1, cache.getCacheHits());
    assertEquals(1, cache.getCacheMisses());
  }

  /** Two clients behind one address, on different versions, must not swap answers. */
  @Test
  void doesNotServeAcrossProtocolVersions() throws Exception {
    MotdCache cache = new MotdCache(60_000L);
    ServerPing forNewClient = mock(ServerPing.class);

    cache.onProxyPing(ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, "mc.example.com",
        forNewClient));
    ProxyPingEvent old = ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_16, "mc.example.com",
        mock(ServerPing.class));
    cache.onProxyPing(old);

    verify(old, never()).setPing(forNewClient);
    assertEquals(0, cache.getCacheHits());
  }

  /** A per-hostname MOTD must not leak to a ping for a different hostname. */
  @Test
  void doesNotServeAcrossVirtualHosts() throws Exception {
    MotdCache cache = new MotdCache(60_000L);
    ServerPing forMainHost = mock(ServerPing.class);

    cache.onProxyPing(ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, "mc.example.com",
        forMainHost));
    ProxyPingEvent other = ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, "eu.example.com",
        mock(ServerPing.class));
    cache.onProxyPing(other);

    verify(other, never()).setPing(forMainHost);
  }

  @Test
  void invalidateDropsEveryEntryForAnAddress() throws Exception {
    MotdCache cache = new MotdCache(60_000L);

    cache.onProxyPing(ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, "mc.example.com",
        mock(ServerPing.class)));
    cache.onProxyPing(ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, "eu.example.com",
        mock(ServerPing.class)));

    assertEquals(true, cache.invalidate(InetAddress.getByName("203.0.113.5")));
    assertEquals(false, cache.invalidate(InetAddress.getByName("203.0.113.5")));
  }

  @Test
  void disabledCacheNeverServesAnything() throws Exception {
    MotdCache cache = new MotdCache(60_000L, false);
    ServerPing response = mock(ServerPing.class);

    cache.onProxyPing(ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, null, response));
    ProxyPingEvent second = ping("203.0.113.5", ProtocolVersion.MINECRAFT_1_21, null,
        mock(ServerPing.class));
    cache.onProxyPing(second);

    verify(second, never()).setPing(response);
  }
}
