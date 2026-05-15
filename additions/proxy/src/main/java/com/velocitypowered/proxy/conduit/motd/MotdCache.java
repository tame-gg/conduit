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

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Caches {@link ServerPing} responses per connecting {@link InetAddress} to reduce the cost of
 * repeated server-list pings from the same host.
 *
 * <p>On a cache hit the cached {@link ServerPing} is applied to the event immediately and the
 * event's result is frozen, preventing downstream listeners from re-computing it.  Stale entries
 * are pruned lazily on each incoming ping request.
 *
 * <p>The singleton {@link #DISABLED} instance performs no caching and registers no listeners.
 */
public class MotdCache {

  /**
   * Sentinel instance used when MOTD caching is disabled.
   * No listeners are registered and no state is maintained.
   */
  public static final MotdCache DISABLED = new MotdCache(2000) {
    @Override
    public void register(Object plugin, ProxyServer proxy) {
      // no-op — MOTD caching is disabled
    }

    @Override
    public void onProxyPing(ProxyPingEvent event) {
      // no-op
    }
  };

  private static final Logger logger = LogManager.getLogger(MotdCache.class);

  private final long ttlMs;
  private final ConcurrentHashMap<InetAddress, CachedPing> cache = new ConcurrentHashMap<>();
  private final LongAdder cacheHits = new LongAdder();
  private final LongAdder cacheMisses = new LongAdder();

  /**
   * Constructs a {@code MotdCache} with the given time-to-live for each cached entry.
   *
   * @param ttlMs the time-to-live in milliseconds for each cached {@link ServerPing}
   */
  public MotdCache(long ttlMs) {
    this.ttlMs = ttlMs;
  }

  /**
   * Registers this cache as a {@link ProxyPingEvent} listener on the given proxy.
   *
   * @param plugin the owning plugin instance used for event registration
   * @param proxy  the proxy server whose event manager will receive registrations
   */
  public void register(Object plugin, ProxyServer proxy) {
    proxy.getEventManager().register(plugin, this);
    logger.info("[Conduit] MotdCache registered (TTL {}ms).", ttlMs);
  }

  /**
   * Handles a {@link ProxyPingEvent} by returning a cached response when available, or caching
   * the computed response for future pings.
   *
   * @param event the ping event
   */
  @Subscribe
  public void onProxyPing(ProxyPingEvent event) {
    InetAddress address = event.getConnection().getRemoteAddress().getAddress();
    long now = System.currentTimeMillis();

    pruneExpired(now);

    CachedPing cached = cache.get(address);
    if (cached != null && now - cached.timestamp() < ttlMs) {
      event.setPing(cached.ping());
      cacheHits.increment();
      return;
    }

    cacheMisses.increment();
    ServerPing ping = event.getPing();
    cache.put(address, new CachedPing(ping, now));
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

  /** Removes all entries whose TTL has elapsed. */
  private void pruneExpired(long now) {
    Iterator<Map.Entry<InetAddress, CachedPing>> it = cache.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<InetAddress, CachedPing> entry = it.next();
      if (now - entry.getValue().timestamp() >= ttlMs) {
        it.remove();
      }
    }
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
}
