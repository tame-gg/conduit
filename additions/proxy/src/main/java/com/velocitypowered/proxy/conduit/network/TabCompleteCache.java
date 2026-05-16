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

import com.velocitypowered.proxy.conduit.diagnostics.ConduitDiagnostics;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Short-TTL cache of backend tab-completion responses keyed on {@code (server, prefix)}.
 *
 * <p>Tab-complete spam is common on creative/build servers — a player holding a key down can fire
 * dozens of completion requests per second. Without caching, every request round-trips to the
 * backend, which (for modded backends) often touches command-tree state and produces a sizeable
 * response. A 1–2 second TTL is short enough that operators rarely notice staleness yet absorbs
 * the spam burst at near-zero CPU on the proxy.
 *
 * <h3>Why per-server keys?</h3>
 * Tab-completion is server-specific: the same prefix on the {@code lobby} server and the
 * {@code modded} server produces different suggestions. Keys include the server name so cached
 * entries cannot leak across backends.
 *
 * <h3>Sizing</h3>
 * The cache is bounded by {@code maxEntries} with LRU eviction (oldest evicted on insert past the
 * cap). Per-server LRU would require deeper bookkeeping; in practice the prefix distribution
 * across servers is sparse, so a single LRU works well.
 *
 * <h3>Lifecycle integration</h3>
 * This class is the data structure only. Wiring it into Velocity's
 * {@code ClientPlaySessionHandler} (which intercepts {@code TabCompleteRequest} packets) is a
 * separate overlay step. The expected pattern in the handler is:
 * <pre>{@code
 * TabCompleteCache cache = Conduit.get().getTabCompleteCache();
 * Optional<TabCompleteCache.CachedResponse> cached = cache.lookup(serverName, prefix);
 * if (cached.isPresent()) {
 *   sendToPlayer(cached.get());
 *   return;
 * }
 * forwardToBackend(req, response -> cache.store(serverName, prefix, response));
 * }</pre>
 *
 * <p>The {@link #DISABLED} sentinel is returned when the cache is turned off in
 * {@code conduit.toml}; all lookups miss, all stores are no-ops, so the hot path keeps the
 * existing behaviour with zero overhead.
 */
public class TabCompleteCache {

  /**
   * Sentinel instance used when tab-complete caching is disabled.
   * All lookups miss; stores are no-ops. The constructor arguments are placeholders that satisfy
   * the superclass; every method that would read them is overridden.
   */
  public static final TabCompleteCache DISABLED = new TabCompleteCache(1, 1, null) {
    @Override
    public Optional<CachedResponse> lookup(String server, String prefix) {
      return Optional.empty();
    }

    @Override
    public void store(String server, String prefix, CachedResponse response) {
      // no-op
    }

    @Override
    public int invalidateServer(String server) {
      return 0;
    }

    @Override
    public int size() {
      return 0;
    }
  };

  private final long ttlMs;
  private final int maxEntries;
  private final ConduitDiagnostics diagnostics;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<Key, TimedResponse> cache;

  /**
   * Constructs a tab-complete cache.
   *
   * @param ttlMs       how long entries remain valid, in milliseconds
   * @param maxEntries  the cap on total cached entries before LRU eviction
   * @param diagnostics the diagnostics instance used to record hits/misses
   */
  public TabCompleteCache(long ttlMs, int maxEntries, ConduitDiagnostics diagnostics) {
    this.ttlMs = ttlMs;
    this.maxEntries = maxEntries;
    this.diagnostics = diagnostics;
    this.cache = new LinkedHashMap<>(64, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Key, TimedResponse> eldest) {
        return size() > TabCompleteCache.this.maxEntries;
      }
    };
  }

  /**
   * Returns a cached response if one exists and has not expired.
   *
   * @param server the backend server name the player is currently connected to
   * @param prefix the tab-complete prefix sent by the player
   */
  public Optional<CachedResponse> lookup(String server, String prefix) {
    Key key = new Key(server, prefix);
    long now = System.currentTimeMillis();
    lock.lock();
    try {
      TimedResponse entry = cache.get(key);
      if (entry == null) {
        diagnostics.recordTabCompleteCacheMiss();
        return Optional.empty();
      }
      if (now - entry.storedAt() >= ttlMs) {
        cache.remove(key);
        diagnostics.recordTabCompleteCacheMiss();
        return Optional.empty();
      }
      diagnostics.recordTabCompleteCacheHit();
      return Optional.of(entry.response());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stores a backend response for later retrieval. Existing entries with the same key are replaced
   * and any expired entries are pruned lazily.
   */
  public void store(String server, String prefix, CachedResponse response) {
    if (response == null) {
      return;
    }
    Key key = new Key(server, prefix);
    long now = System.currentTimeMillis();
    lock.lock();
    try {
      cache.put(key, new TimedResponse(response, now));
      pruneExpired(now);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes every cached entry whose key references {@code server}. Returns the number of evicted
   * entries. Call this when a player switches servers or when a backend is unregistered.
   */
  public int invalidateServer(String server) {
    lock.lock();
    try {
      int before = cache.size();
      cache.entrySet().removeIf(e -> e.getKey().server.equals(server));
      return before - cache.size();
    } finally {
      lock.unlock();
    }
  }

  /** Returns the total number of cached entries. */
  public int size() {
    lock.lock();
    try {
      return cache.size();
    } finally {
      lock.unlock();
    }
  }

  /** Removes expired entries. Caller must hold the lock. */
  private void pruneExpired(long now) {
    Iterator<Map.Entry<Key, TimedResponse>> it = cache.entrySet().iterator();
    while (it.hasNext()) {
      if (now - it.next().getValue().storedAt() >= ttlMs) {
        it.remove();
      }
    }
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * Opaque holder for a backend tab-complete response.
   *
   * @param suggestions      the ordered list of suggested completion strings
   * @param transactionId    the transaction id the backend assigned (1.13+ only; {@code -1} for
   *                         legacy clients that omit it)
   * @param rangeStart       the start offset within the player's input that the suggestion replaces
   * @param rangeLength      the length within the player's input that the suggestion replaces
   */
  public record CachedResponse(
      List<String> suggestions,
      int transactionId,
      int rangeStart,
      int rangeLength) {

    /** Convenience constructor for legacy responses without range/transaction info. */
    public static CachedResponse legacy(List<String> suggestions) {
      return new CachedResponse(List.copyOf(suggestions), -1, 0, 0);
    }
  }

  private record Key(String server, String prefix) {
    private Key {
      Objects.requireNonNull(server, "server");
      Objects.requireNonNull(prefix, "prefix");
    }
  }

  private record TimedResponse(CachedResponse response, long storedAt) {}
}
