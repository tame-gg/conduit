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

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Caches {@link ServerPing} responses to reduce the cost of repeated server-list pings from the
 * same host.
 *
 * <h3>Cache key</h3>
 * An entry is keyed by the connecting address <em>together with</em> the client's protocol version
 * and the hostname it connected to. All three change what the correct answer is: a ping response
 * commonly carries a version-specific label or a per-forced-host MOTD, and one address can be a
 * whole household or a carrier NAT. Keying on the address alone would hand one client the answer
 * built for another.
 *
 * <p>The listener runs at {@link PostOrder#LATE} so other plugins still see the event first
 * and can mutate the ping; the cache reads the final ping after them and serves it on subsequent
 * hits.  Stale entries are pruned lazily on cache writes; LRU eviction caps memory at
 * {@value #MAX_ENTRIES} unique keys.
 *
 * <p>Caching can be switched off — and back on — at runtime via {@link #setEnabled(boolean)}.
 */
public class MotdCache {

  private static final Logger logger = LogManager.getLogger(MotdCache.class);

  /** Cap on distinct cache keys held; protects against IPv6 scanner flooding. */
  static final int MAX_ENTRIES = 4096;

  private volatile boolean enabled;
  private volatile long ttlMs;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<Key, CachedPing> cache =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, CachedPing> eldest) {
          return size() > MAX_ENTRIES;
        }
      };
  private final LongAdder cacheHits = new LongAdder();
  private final LongAdder cacheMisses = new LongAdder();

  /**
   * Constructs a {@code MotdCache} with the given time-to-live for each cached entry.
   *
   * @param ttlMs the time-to-live in milliseconds for each cached {@link ServerPing}
   */
  public MotdCache(long ttlMs) {
    this(ttlMs, true);
  }

  /**
   * Constructs a {@code MotdCache} in the given initial state.
   *
   * @param ttlMs   the time-to-live in milliseconds for each cached {@link ServerPing}
   * @param enabled whether caching is active; a disabled cache neither reads nor stores entries
   */
  public MotdCache(long ttlMs, boolean enabled) {
    this.ttlMs = ttlMs;
    this.enabled = enabled;
  }

  /**
   * Registers this cache as a {@link ProxyPingEvent} listener on the given proxy.
   *
   * <p>The listener is registered whether or not caching is currently enabled, so that
   * {@code /conduit reload} can switch it on without a restart; a disabled cache returns from the
   * handler immediately.
   *
   * @param plugin the owning plugin instance used for event registration
   * @param proxy  the proxy server whose event manager will receive registrations
   */
  public void register(Object plugin, ProxyServer proxy) {
    proxy.getEventManager().register(plugin, this);
    logger.info("[Conduit] MotdCache registered (enabled={}, TTL {}ms).", enabled, ttlMs);
  }

  /** Returns whether MOTD caching is currently active. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Turns MOTD caching on or off at runtime. Cached entries are dropped when it is turned off. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (!enabled) {
      clearAll();
    }
  }

  /**
   * Handles a {@link ProxyPingEvent} by returning a cached response when available, or caching
   * the computed response for future pings.
   *
   * @param event the ping event
   */
  @Subscribe(order = PostOrder.LATE)
  public void onProxyPing(ProxyPingEvent event) {
    if (!enabled) {
      return;
    }
    Key key = keyFor(event);
    long now = System.currentTimeMillis();

    lock.lock();
    try {
      CachedPing cached = cache.get(key);
      if (cached != null && now - cached.timestamp() < ttlMs) {
        event.setPing(cached.ping());
        cacheHits.increment();
        return;
      }

      cacheMisses.increment();
      cache.put(key, new CachedPing(event.getPing(), now));
      pruneExpired(now);
    } finally {
      lock.unlock();
    }
  }

  private static Key keyFor(ProxyPingEvent event) {
    InetAddress address = event.getConnection().getRemoteAddress().getAddress();
    int protocol = event.getConnection().getProtocolVersion().getProtocol();
    String virtualHost = event.getConnection().getVirtualHost()
        .map(host -> host.getHostString().toLowerCase(Locale.ROOT))
        .orElse("");
    return new Key(address, protocol, virtualHost);
  }

  /**
   * Returns the total number of cache hits since this instance was created.
   *
   * @return cumulative cache hit count
   */
  public long getCacheHits() {
    return cacheHits.sum();
  }

  /**
   * Returns the total number of cache misses since this instance was created.
   *
   * @return cumulative cache miss count
   */
  public long getCacheMisses() {
    return cacheMisses.sum();
  }

  /** Returns the current MOTD cache TTL in milliseconds. */
  public long getTtlMs() {
    return ttlMs;
  }

  /** Updates the MOTD cache TTL for future lookups. */
  public void setTtlMs(long ttlMs) {
    this.ttlMs = ttlMs;
  }

  /**
   * Removes any cached ping for the given remote address.
   *
   * @return {@code true} if an entry was removed
   */
  public boolean invalidate(InetAddress address) {
    lock.lock();
    try {
      // One address can hold several entries (different client versions or hostnames).
      return cache.keySet().removeIf(key -> key.address().equals(address));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes every cached entry. Returns the number of evicted entries.
   */
  public int clearAll() {
    lock.lock();
    try {
      int previous = cache.size();
      cache.clear();
      return previous;
    } finally {
      lock.unlock();
    }
  }

  /** Removes all entries whose TTL has elapsed. Caller must hold the lock. */
  private void pruneExpired(long now) {
    cache.entrySet().removeIf(e -> now - e.getValue().timestamp() >= ttlMs);
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * Immutable holder for a cached {@link ServerPing} and the wall-clock time it was stored.
   *
   * @param ping      the cached ping response
   * @param timestamp the {@link System#currentTimeMillis()} value when this entry was created
   */
  private record CachedPing(ServerPing ping, long timestamp) {
  }

  /**
   * Identity of a cacheable ping: who asked, with which protocol, and under which hostname.
   *
   * @param address     the connecting address
   * @param protocol    the client's protocol version number
   * @param virtualHost the lower-cased hostname the client connected to, or {@code ""} if none
   */
  private record Key(InetAddress address, int protocol, String virtualHost) {
  }
}
