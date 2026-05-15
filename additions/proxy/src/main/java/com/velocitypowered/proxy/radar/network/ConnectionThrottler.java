/*
 * Conduit — a performance-focused fork of Velocity for modded networks.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.velocitypowered.proxy.radar.network;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Limits the rate at which new TCP connections are accepted from any single IP address.
 *
 * <p>Vanilla Velocity has a global connection rate limit (login-ratelimit) that counts time between
 * two logins from the same IP. That check happens after the TCP handshake and the initial
 * handshake packet are already processed, so it does not protect against low-level TCP floods.
 *
 * <p>This throttler acts at the Netty channel-init level (before any data is read) by tracking
 * connection attempts per IP per second.  If an IP exceeds the configured limit, the channel is
 * closed immediately, saving memory and CPU that vanilla would spend on handshake processing.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>A sliding window per IP: connections are counted in 1-second buckets.</li>
 *   <li>The map is LRU-bounded at 8 192 entries to prevent unbounded growth during attacks.</li>
 *   <li>The lock is coarse-grained; the map is only accessed at connection-accept time (not on the
 *       hot packet path), so contention is acceptable.</li>
 * </ul>
 */
public final class ConnectionThrottler {

  /** Returned when throttling is disabled.  All checks return {@code false} (not throttled). */
  public static final ConnectionThrottler UNLIMITED = new ConnectionThrottler(Integer.MAX_VALUE) {
    @Override public boolean isThrottled(InetAddress addr) { return false; }
  };

  private static final Logger logger = LogManager.getLogger(ConnectionThrottler.class);
  private static final int MAX_TRACKED_IPS = 8192;

  private volatile int maxPerSecond;
  private final ReentrantLock lock = new ReentrantLock();

  private final LinkedHashMap<InetAddress, IpWindow> windows =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<InetAddress, IpWindow> eldest) {
          return size() > MAX_TRACKED_IPS;
        }
      };

  public ConnectionThrottler(int maxPerSecond) {
    this.maxPerSecond = maxPerSecond;
  }

  /**
   * Returns {@code true} if the connection from {@code addr} should be dropped immediately because
   * the IP has exceeded the configured rate limit.
   *
   * <p>This method is safe to call from any Netty thread (boss group).
   */
  public boolean isThrottled(InetAddress addr) {
    long now = System.currentTimeMillis();
    lock.lock();
    try {
      IpWindow w = windows.computeIfAbsent(addr, k -> new IpWindow());
      boolean throttled = w.record(now, maxPerSecond);
      if (throttled) {
        logger.warn("[Conduit] ConnectionThrottle: dropping connection from {} (>{}/s)",
            addr.getHostAddress(), maxPerSecond);
      }
      return throttled;
    } finally {
      lock.unlock();
    }
  }

  public void setMaxPerSecond(int maxPerSecond) {
    this.maxPerSecond = maxPerSecond;
  }

  /** Returns the number of IPs currently tracked (for diagnostics). */
  public int trackedIpCount() {
    lock.lock();
    try { return windows.size(); }
    finally { lock.unlock(); }
  }

  /** Clears all tracking state (e.g., after a config reload). */
  public void reset() {
    lock.lock();
    try { windows.clear(); }
    finally { lock.unlock(); }
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /** Sliding 1-second window for a single IP. */
  private static final class IpWindow {
    private long windowStart = 0;
    private int count = 0;

    /** Records a new connection attempt. Returns {@code true} if the rate limit is exceeded. */
    boolean record(long nowMs, int limit) {
      if (nowMs - windowStart >= 1000) {
        windowStart = nowMs;
        count = 1;
        return false;
      }
      return ++count > limit;
    }
  }
}
