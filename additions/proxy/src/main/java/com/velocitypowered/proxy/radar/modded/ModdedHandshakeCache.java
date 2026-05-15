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

import java.net.InetAddress;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Caches the outcome of a completed mod-list negotiation so that a re-connecting client can skip
 * the handshake round-trip entirely.
 *
 * <p>Cache key: (client IP, sorted mod fingerprint).  The fingerprint is a sorted, comma-joined
 * list of "modid@version" strings so that mod-order differences across connections do not produce
 * spurious cache misses.
 *
 * <p>The cache is bounded (max 2 048 entries) with LRU eviction and a configurable TTL.
 * Thread-safe via a read-write lock — reads on the hot path acquire only a read lock.
 */
public final class ModdedHandshakeCache {

  /** A no-op instance returned when handshake caching is disabled in the config. */
  public static final ModdedHandshakeCache NOOP = new ModdedHandshakeCache(0) {
    @Override public CacheEntry get(InetAddress addr, List<String> mods)  { return null; }
    @Override public void put(InetAddress addr, List<String> mods, CacheEntry entry) {}
    @Override public void invalidate(InetAddress addr) {}
    @Override public int size() { return 0; }
  };

  private static final Logger logger = LogManager.getLogger(ModdedHandshakeCache.class);
  private static final int MAX_ENTRIES = 2048;

  private volatile int ttlSeconds;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  // LinkedHashMap in access-order mode gives LRU eviction for free.
  private final LinkedHashMap<CacheKey, TimedEntry> cache =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, TimedEntry> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  public ModdedHandshakeCache(int ttlSeconds) {
    this.ttlSeconds = ttlSeconds;
  }

  /**
   * Looks up a previous negotiation result.
   *
   * @return the cached entry, or {@code null} if absent or expired
   */
  public CacheEntry get(InetAddress addr, List<String> mods) {
    if (ttlSeconds <= 0) return null;
    CacheKey key = new CacheKey(addr, mods);
    long now = System.currentTimeMillis();

    lock.readLock().lock();
    try {
      TimedEntry te = cache.get(key);
      if (te == null) return null;
      if (now - te.storedAt > (long) ttlSeconds * 1000) return null;
      return te.entry;
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Stores a negotiation result. Expired entries are pruned lazily on write. */
  public void put(InetAddress addr, List<String> mods, CacheEntry entry) {
    if (ttlSeconds <= 0) return;
    CacheKey key = new CacheKey(addr, mods);
    TimedEntry te = new TimedEntry(entry, System.currentTimeMillis());

    lock.writeLock().lock();
    try {
      cache.put(key, te);
      pruneExpired();
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Removes all entries for a given client IP (e.g. on disconnect after error). */
  public void invalidate(InetAddress addr) {
    lock.writeLock().lock();
    try {
      cache.entrySet().removeIf(e -> e.getKey().address.equals(addr));
    } finally {
      lock.writeLock().unlock();
    }
  }

  public int size() {
    lock.readLock().lock();
    try { return cache.size(); }
    finally { lock.readLock().unlock(); }
  }

  public void setTtlSeconds(int ttlSeconds) {
    this.ttlSeconds = ttlSeconds;
    if (ttlSeconds <= 0) {
      lock.writeLock().lock();
      try { cache.clear(); }
      finally { lock.writeLock().unlock(); }
    }
  }

  private void pruneExpired() {
    if (ttlSeconds <= 0) return;
    long cutoff = System.currentTimeMillis() - (long) ttlSeconds * 1000;
    Iterator<Map.Entry<CacheKey, TimedEntry>> it = cache.entrySet().iterator();
    int removed = 0;
    while (it.hasNext()) {
      if (it.next().getValue().storedAt < cutoff) { it.remove(); removed++; }
    }
    if (removed > 0) {
      logger.debug("[Conduit] ModdedHandshakeCache pruned {} expired entries", removed);
    }
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * The cached handshake outcome: the server's known-packs list that was accepted by the client,
   * plus any additional state that should be replayed on re-connect.
   */
  public record CacheEntry(
      List<String> acceptedPackNamespaces,
      String negotiatedForgeChannel,
      long negotiatedAtMs
  ) {}

  private record CacheKey(InetAddress address, String fingerprint) {
    CacheKey(InetAddress address, List<String> mods) {
      this(address, buildFingerprint(mods));
    }

    private static String buildFingerprint(List<String> mods) {
      return mods.stream().sorted().reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CacheKey c)) return false;
      return Objects.equals(address, c.address) && Objects.equals(fingerprint, c.fingerprint);
    }

    @Override
    public int hashCode() {
      return Objects.hash(address, fingerprint);
    }
  }

  private record TimedEntry(CacheEntry entry, long storedAt) {}
}
