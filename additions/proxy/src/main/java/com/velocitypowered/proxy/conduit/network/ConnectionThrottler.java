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

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Limits the rate at which new TCP connections are accepted from any single source network.
 *
 * <p>Vanilla Velocity has a global connection rate limit (login-ratelimit) that counts time between
 * two logins from the same IP. That check happens after the TCP handshake and the initial
 * handshake packet are already processed, so it does not protect against low-level TCP floods.
 *
 * <p>This throttler acts at the Netty channel-init level (before any data is read) by tracking
 * connection attempts per source network per second.  If a source exceeds the configured limit, the
 * channel is closed immediately, saving memory and CPU that vanilla would spend on handshake
 * processing.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>A sliding window per source: connections are counted against a 1-second window.</li>
 *   <li>Sources are grouped by {@link SubnetKey}, so an attacker cannot escape the counter by
 *       rotating the host bits of an IPv6 allocation.</li>
 *   <li>The map is LRU-bounded at 8 192 entries to prevent unbounded growth during attacks.</li>
 *   <li>The lock is coarse-grained; the map is only accessed at connection-accept time (not on the
 *       hot packet path), so contention is acceptable.</li>
 *   <li>Drops are logged in aggregate, never one line per dropped connection: a flood is exactly
 *       when the log would otherwise turn into its own denial of service.</li>
 * </ul>
 *
 * <p>Throttling can be switched off — and back on — at runtime via {@link #setEnabled(boolean)},
 * so {@code /conduit reload} can apply a change to {@code connection-throttle} without a restart.
 */
public class ConnectionThrottler {

  private static final Logger logger = LogManager.getLogger(ConnectionThrottler.class);
  private static final int MAX_TRACKED_SOURCES = 8192;

  /** Default IPv4 prefix: every address is its own source. */
  public static final int DEFAULT_IPV4_PREFIX = 32;

  /** Default IPv6 prefix: the smallest block normally allocated to a single subscriber. */
  public static final int DEFAULT_IPV6_PREFIX = 64;

  /** Default gap between aggregated drop reports for one source. */
  public static final long DEFAULT_LOG_INTERVAL_MS = 5000L;

  private volatile boolean enabled;
  private volatile int maxPerSecond;
  private volatile int ipv4Prefix = DEFAULT_IPV4_PREFIX;
  private volatile int ipv6Prefix = DEFAULT_IPV6_PREFIX;
  private volatile long logIntervalMs = DEFAULT_LOG_INTERVAL_MS;
  private final ReentrantLock lock = new ReentrantLock();

  private final LinkedHashMap<SubnetKey, SourceWindow> windows =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SubnetKey, SourceWindow> eldest) {
          return size() > MAX_TRACKED_SOURCES;
        }
      };

  /**
   * Constructs an enabled throttler that allows at most {@code maxPerSecond} new connections per
   * source network.
   *
   * @param maxPerSecond the maximum connection rate per source per second
   */
  public ConnectionThrottler(int maxPerSecond) {
    this(maxPerSecond, true);
  }

  /**
   * Constructs a throttler in the given initial state.
   *
   * @param maxPerSecond the maximum connection rate per source per second
   * @param enabled      whether throttling is active; a disabled throttler admits every connection
   */
  public ConnectionThrottler(int maxPerSecond, boolean enabled) {
    this.maxPerSecond = maxPerSecond;
    this.enabled = enabled;
  }

  /**
   * Returns {@code true} if the connection from {@code addr} should be dropped immediately because
   * its source network has exceeded the configured rate limit.
   *
   * <p>This method is safe to call from any Netty thread (boss group).
   */
  public boolean isThrottled(InetAddress addr) {
    if (!enabled) {
      return false;
    }
    SubnetKey key = SubnetKey.of(addr, ipv4Prefix, ipv6Prefix);
    long now = System.currentTimeMillis();
    long suppressed = -1;
    lock.lock();
    try {
      SourceWindow w = windows.computeIfAbsent(key, k -> new SourceWindow());
      if (!w.record(now, maxPerSecond)) {
        return false;
      }
      suppressed = w.noteDrop(now, logIntervalMs);
    } finally {
      lock.unlock();
    }
    // Logged outside the lock: a flood makes this the hottest path in the proxy.
    if (suppressed >= 0) {
      logger.warn("[Conduit] ConnectionThrottle: dropping connections from {} (>{}/s);"
          + " {} dropped since the last report.", key, maxPerSecond, suppressed + 1);
    }
    return true;
  }

  /** Returns whether throttling is currently active. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Turns throttling on or off at runtime. Tracking state is cleared when it is turned off. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (!enabled) {
      reset();
    }
  }

  /** Updates the maximum connections per second per source network. */
  public void setMaxPerSecond(int maxPerSecond) {
    this.maxPerSecond = maxPerSecond;
  }

  /** Returns the current maximum connections per second per source network. */
  public int getMaxPerSecond() {
    return maxPerSecond;
  }

  /**
   * Sets the prefix lengths used to group source addresses. Changing them clears tracking state,
   * because existing keys were masked with the previous prefixes.
   *
   * @param ipv4Prefix prefix applied to IPv4 sources (1–32)
   * @param ipv6Prefix prefix applied to IPv6 sources (1–128)
   */
  public void setPrefixes(int ipv4Prefix, int ipv6Prefix) {
    if (this.ipv4Prefix == ipv4Prefix && this.ipv6Prefix == ipv6Prefix) {
      return;
    }
    this.ipv4Prefix = ipv4Prefix;
    this.ipv6Prefix = ipv6Prefix;
    reset();
  }

  /** Returns the prefix length applied to IPv4 sources. */
  public int getIpv4Prefix() {
    return ipv4Prefix;
  }

  /** Returns the prefix length applied to IPv6 sources. */
  public int getIpv6Prefix() {
    return ipv6Prefix;
  }

  /** Sets the minimum gap between aggregated drop reports for a single source. */
  public void setLogIntervalMs(long logIntervalMs) {
    this.logIntervalMs = logIntervalMs;
  }

  /** Returns the minimum gap between aggregated drop reports for a single source. */
  public long getLogIntervalMs() {
    return logIntervalMs;
  }

  /** Returns the number of source networks currently tracked (for diagnostics). */
  public int trackedIpCount() {
    lock.lock();
    try {
      return windows.size();
    } finally {
      lock.unlock();
    }
  }

  /** Clears all tracking state (e.g., after a config reload). */
  public void reset() {
    lock.lock();
    try {
      windows.clear();
    } finally {
      lock.unlock();
    }
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * Sliding 1-second window for a single source network, implemented as a ring buffer of connection
   * timestamps. Old timestamps fall off as time advances, so two back-to-back bursts that
   * straddle a one-second boundary cannot exceed the configured rate.
   */
  private static final class SourceWindow {

    /**
     * Capacity bounds the worst-case work per {@code record} call. Connections beyond capacity
     * within one window are throttled regardless of {@code limit}, which is the desired behaviour:
     * a single source firing &gt;{@value} connections per second is already abusive.
     */
    private static final int CAPACITY = 256;
    private static final long WINDOW_MS = 1000L;

    private final long[] timestamps = new long[CAPACITY];
    private int head = 0;
    private int size = 0;

    /** Drops since the last report was emitted for this source. */
    private long suppressedDrops = 0;
    private long lastReportAt = 0;

    /** Records a new connection attempt. Returns {@code true} if the rate limit is exceeded. */
    boolean record(long nowMs, int limit) {
      long cutoff = nowMs - WINDOW_MS;
      // Drop entries that have fallen out of the window. Tail = head - size (mod CAPACITY).
      while (size > 0) {
        int tail = (head - size + CAPACITY) % CAPACITY;
        if (timestamps[tail] <= cutoff) {
          size--;
        } else {
          break;
        }
      }
      if (size >= limit) {
        return true;
      }
      timestamps[head] = nowMs;
      head = (head + 1) % CAPACITY;
      if (size < CAPACITY) {
        size++;
      }
      return false;
    }

    /**
     * Accounts for one dropped connection.
     *
     * @return the number of drops suppressed since the previous report when a report is due now,
     *         or {@code -1} when this drop should stay silent
     */
    long noteDrop(long nowMs, long intervalMs) {
      if (lastReportAt != 0 && nowMs - lastReportAt < intervalMs) {
        suppressedDrops++;
        return -1;
      }
      long suppressed = suppressedDrops;
      suppressedDrops = 0;
      lastReportAt = nowMs;
      return suppressed;
    }
  }
}
