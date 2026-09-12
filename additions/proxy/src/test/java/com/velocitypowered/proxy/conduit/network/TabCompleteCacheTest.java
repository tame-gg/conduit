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

package com.velocitypowered.proxy.conduit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.velocitypowered.proxy.conduit.diagnostics.ConduitDiagnostics;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TabCompleteCacheTest {

  private static TabCompleteCache cache() {
    return new TabCompleteCache(60_000L, 64, mock(ConduitDiagnostics.class));
  }

  private static TabCompleteCache.CachedResponse response(String... suggestions) {
    return TabCompleteCache.CachedResponse.legacy(List.of(suggestions));
  }

  @Test
  void servesAnEntryBackToTheSamePlayer() {
    TabCompleteCache cache = cache();
    UUID player = UUID.randomUUID();

    cache.store(player, "lobby", "/", response("/spawn"));

    assertTrue(cache.lookup(player, "lobby", "/").isPresent());
    assertEquals(List.of("/spawn"), cache.lookup(player, "lobby", "/").get().suggestions());
  }

  /**
   * The leak this cache used to have: suggestions are permission-filtered for whoever asked, so an
   * administrator's completion list must not be replayed to the next player who types the same
   * thing.
   */
  @Test
  void doesNotServeOnePlayersSuggestionsToAnother() {
    TabCompleteCache cache = cache();
    UUID admin = UUID.randomUUID();
    UUID everyoneElse = UUID.randomUUID();

    cache.store(admin, "lobby", "/", response("/ban", "/stop"));

    assertFalse(cache.lookup(everyoneElse, "lobby", "/").isPresent());
  }

  @Test
  void doesNotServeSuggestionsAcrossServers() {
    TabCompleteCache cache = cache();
    UUID player = UUID.randomUUID();

    cache.store(player, "lobby", "/", response("/spawn"));

    assertFalse(cache.lookup(player, "modded", "/").isPresent());
  }

  @Test
  void invalidatesByServerAndByPlayer() {
    TabCompleteCache cache = cache();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    cache.store(first, "lobby", "/", response("/spawn"));
    cache.store(second, "lobby", "/", response("/spawn"));
    cache.store(first, "modded", "/", response("/spawn"));
    assertEquals(3, cache.size());

    assertEquals(2, cache.invalidateServer("lobby"));
    assertEquals(1, cache.invalidatePlayer(first));
    assertEquals(0, cache.size());
  }

  @Test
  void expiredEntriesMiss() throws Exception {
    TabCompleteCache cache = new TabCompleteCache(1L, 64, mock(ConduitDiagnostics.class));
    UUID player = UUID.randomUUID();

    cache.store(player, "lobby", "/", response("/spawn"));
    Thread.sleep(3);

    assertFalse(cache.lookup(player, "lobby", "/").isPresent());
  }

  @Test
  void disabledCacheStoresNothingUntilSwitchedOn() {
    TabCompleteCache cache =
        new TabCompleteCache(60_000L, 64, mock(ConduitDiagnostics.class), false);
    UUID player = UUID.randomUUID();

    cache.store(player, "lobby", "/", response("/spawn"));
    assertEquals(0, cache.size());
    assertFalse(cache.lookup(player, "lobby", "/").isPresent());

    cache.setEnabled(true);
    cache.store(player, "lobby", "/", response("/spawn"));
    assertTrue(cache.lookup(player, "lobby", "/").isPresent());

    cache.setEnabled(false);
    assertEquals(0, cache.size());
  }
}
