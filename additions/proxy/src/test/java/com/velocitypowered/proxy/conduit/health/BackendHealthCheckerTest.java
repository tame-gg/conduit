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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackendHealthCheckerTest {

  /** A backend whose ping outcome is swapped between rounds. */
  private static final class FakeBackend {

    final RegisteredServer server = mock(RegisteredServer.class);
    final AtomicReference<Boolean> reachable = new AtomicReference<>(true);

    FakeBackend(String name) {
      when(server.getServerInfo())
          .thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25566)));
      when(server.ping()).thenAnswer(invocation -> reachable.get()
          ? CompletableFuture.completedFuture(mock(ServerPing.class))
          : CompletableFuture.failedFuture(new IOException("refused")));
    }
  }

  private static ProxyServer proxyWith(FakeBackend backend) {
    ProxyServer proxy = mock(ProxyServer.class);
    when(proxy.getAllServers()).thenAnswer(invocation -> List.of(backend.server));
    return proxy;
  }

  /**
   * Drives check rounds synchronously: the fake backend hands back already-completed futures, so
   * every {@code whenComplete} callback runs inline and the state is settled when this returns.
   */
  private static void rounds(BackendHealthChecker checker, ProxyServer proxy, int count) {
    for (int i = 0; i < count; i++) {
      checker.runChecks(proxy);
    }
  }

  @Test
  void oneFailedPingKeepsTheBackendHealthy() throws Exception {
    FakeBackend backend = new FakeBackend("lobby");
    ProxyServer proxy = proxyWith(backend);
    BackendHealthChecker checker = new BackendHealthChecker(10L, 3, 2, true);

    backend.reachable.set(false);
    rounds(checker, proxy, 2);

    assertTrue(checker.isHealthy(backend.server),
        "two missed pings against a threshold of three must not pull a backend out of routing");
  }

  @Test
  void repeatedFailuresMarkUnhealthyAndRepeatedSuccessesRecover() throws Exception {
    FakeBackend backend = new FakeBackend("lobby");
    ProxyServer proxy = proxyWith(backend);
    BackendHealthChecker checker = new BackendHealthChecker(5L, 2, 2, true);

    backend.reachable.set(false);
    rounds(checker, proxy, 1);
    assertTrue(checker.isHealthy(backend.server), "one failure is below the threshold");
    rounds(checker, proxy, 1);
    assertFalse(checker.isHealthy(backend.server), "the second failure crosses the threshold");

    backend.reachable.set(true);
    rounds(checker, proxy, 1);
    assertFalse(checker.isHealthy(backend.server), "one success is below the recovery threshold");
    rounds(checker, proxy, 1);
    assertTrue(checker.isHealthy(backend.server));
  }

  @Test
  void drainedServersStayHealthyButStopBeingRoutable() {
    FakeBackend backend = new FakeBackend("lobby");
    BackendHealthChecker checker = new BackendHealthChecker(10_000L, 3, 2, true);

    assertTrue(checker.isRoutable(backend.server));
    assertTrue(checker.setDrained("lobby", true));
    assertFalse(checker.setDrained("lobby", true));

    assertTrue(checker.isHealthy(backend.server), "draining is not a health state");
    assertFalse(checker.isRoutable(backend.server));
    assertTrue(checker.getDrainedServers().contains("lobby"));
    assertTrue(checker.getHealthSummary().contains("DRAINING"));

    assertTrue(checker.setDrained("lobby", false));
    assertTrue(checker.isRoutable(backend.server));
  }

  @Test
  void disabledCheckerReportsEverythingHealthy() throws Exception {
    FakeBackend backend = new FakeBackend("lobby");
    ProxyServer proxy = proxyWith(backend);
    BackendHealthChecker checker = new BackendHealthChecker(5L, 1, 1, false);

    backend.reachable.set(false);
    rounds(checker, proxy, 5);

    assertFalse(checker.isEnabled());
    assertTrue(checker.isHealthy(backend.server));
  }
}
