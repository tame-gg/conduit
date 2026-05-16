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

package com.velocitypowered.proxy.conduit.security;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Detects bots by tracking incomplete handshakes — TCP connections that open a channel but never
 * send a Login Start packet within the configured timeout.
 *
 * <p>Each IP is tracked with a sliding 60-second window of incomplete-handshake counts. If a
 * single IP exceeds the configured threshold within that window, all subsequent connections from
 * that IP are rejected at the channel-init level.
 *
 * <p>The internal map is LRU-bounded at 8 192 entries to prevent unbounded growth during an
 * attack. The lock is coarse-grained and used only on the connection-accept path, not on the
 * hot packet path.
 *
 * <p>Blocks are not permanent: once an IP is blocked, the block expires after
 * {@value #BLOCK_DURATION_MS} ms of inactivity (no further incomplete handshakes within the
 * 60-second window).  This allows legitimate clients on shared IPs (carrier-grade NAT, VPN exit
 * nodes) to recover without manual intervention.
 *
 * <p>The singleton {@link #DISABLED} instance performs no tracking and always reports every IP
 * as unblocked.
 */
public class BotFilter {

  /**
   * Sentinel instance used when bot filtering is disabled.
   * All operations are no-ops and {@link #isBlocked} always returns {@code false}.
   */
  public static final BotFilter DISABLED = new BotFilter(3000, 10) {
    @Override
    public void recordHandshakeStart(InetAddress addr) {
      // no-op
    }

    @Override
    public void recordHandshakeComplete(InetAddress addr) {
      // no-op
    }

    @Override
    public void recordHandshakeTimeout(InetAddress addr) {
      // no-op
    }

    @Override
    public boolean isBlocked(InetAddress addr) {
      return false;
    }
  };

  private static final Logger logger = LogManager.getLogger(BotFilter.class);
  private static final int MAX_TRACKED_IPS = 8192;
  private static final long WINDOW_MS = 60_000L;
  /** How long an IP remains blocked after the most recent incomplete handshake. */
  static final long BLOCK_DURATION_MS = 10 * 60_000L; // 10 minutes

  private final long handshakeTimeoutMs;
  private final int threshold;
  private final ReentrantLock lock = new ReentrantLock();

  private final LinkedHashMap<InetAddress, IpRecord> records =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<InetAddress, IpRecord> eldest) {
          return size() > MAX_TRACKED_IPS;
        }
      };

  /**
   * Constructs a {@code BotFilter} with the given timeout and block threshold.
   *
   * @param handshakeTimeoutMs connections that do not complete a handshake within this many
   *                           milliseconds are counted as incomplete
   * @param threshold          the number of incomplete handshakes within a 60-second window that
   *                           triggers a block on the offending IP
   */
  public BotFilter(long handshakeTimeoutMs, int threshold) {
    this.handshakeTimeoutMs = handshakeTimeoutMs;
    this.threshold = threshold;
  }

  /**
   * Records the start of a new handshake attempt from the given address.
   *
   * <p>This should be called when the TCP channel is initialised, before any data is read.
   *
   * @param addr the remote IP address
   */
  public void recordHandshakeStart(InetAddress addr) {
    long now = System.currentTimeMillis();
    lock.lock();
    try {
      records.computeIfAbsent(addr, k -> new IpRecord()).pendingStart = now;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Records that a handshake from the given address was completed successfully (Login Start
   * received).  Clears the pending-start timestamp so it is not counted as incomplete.
   *
   * @param addr the remote IP address
   */
  public void recordHandshakeComplete(InetAddress addr) {
    lock.lock();
    try {
      IpRecord rec = records.get(addr);
      if (rec != null) {
        rec.pendingStart = 0;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Records that a handshake from the given address timed out (no Login Start received within
   * {@code handshakeTimeoutMs}).  Increments the incomplete counter and may trigger a block.
   *
   * @param addr the remote IP address
   */
  public void recordHandshakeTimeout(InetAddress addr) {
    long now = System.currentTimeMillis();
    lock.lock();
    try {
      IpRecord rec = records.computeIfAbsent(addr, k -> new IpRecord());
      rec.pendingStart = 0;
      rec.addIncomplete(now);
      rec.lastIncompleteAt = now;
      if (rec.incompleteCount(now) >= threshold && !rec.blocked) {
        rec.blocked = true;
        logger.warn("[Conduit] BotFilter: blocking {} — {} incomplete handshakes in {}s window"
                + " (expires in {}m).",
            addr.getHostAddress(), threshold, WINDOW_MS / 1000, BLOCK_DURATION_MS / 60_000);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns {@code true} if the given IP address is currently blocked due to exceeding the
   * incomplete-handshake threshold.
   *
   * @param addr the remote IP address to check
   * @return {@code true} if this IP should be rejected at channel-init time
   */
  public boolean isBlocked(InetAddress addr) {
    long now = System.currentTimeMillis();
    lock.lock();
    try {
      IpRecord rec = records.get(addr);
      if (rec == null || !rec.blocked) {
        return false;
      }
      if (now - rec.lastIncompleteAt >= BLOCK_DURATION_MS) {
        rec.blocked = false;
        logger.info("[Conduit] BotFilter: unblocking {} (block expired after {}m of inactivity).",
            addr.getHostAddress(), BLOCK_DURATION_MS / 60_000);
        return false;
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Manually unblocks the given IP. Intended for admin commands; returns {@code true} if the IP
   * was actually blocked.
   */
  public boolean unblock(InetAddress addr) {
    lock.lock();
    try {
      IpRecord rec = records.get(addr);
      if (rec == null || !rec.blocked) {
        return false;
      }
      rec.blocked = false;
      logger.info("[Conduit] BotFilter: manually unblocking {}.", addr.getHostAddress());
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** Returns the configured handshake timeout in milliseconds. */
  public long getHandshakeTimeoutMs() {
    return handshakeTimeoutMs;
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * Per-IP tracking record: maintains a pending-handshake start time, a circular timestamp
   * buffer of incomplete events within the sliding window, and a blocked flag.
   */
  private static final class IpRecord {

    /** Wall-clock time (ms) when the most recent handshake started; 0 if none pending. */
    long pendingStart = 0;

    /** Whether this IP is currently blocked. Time-bounded by {@link #BLOCK_DURATION_MS}. */
    boolean blocked = false;

    /** Wall-clock time (ms) of the most recent incomplete handshake. Drives block expiry. */
    long lastIncompleteAt = 0;

    /**
     * Ring buffer of timestamps (ms) for incomplete handshake events within the window.
     * 64 slots is generous; we only need enough headroom to count up to the threshold.
     */
    private final long[] timestamps = new long[64];
    private int head = 0;
    private int size = 0;

    /**
     * Adds a new incomplete-handshake timestamp to the ring buffer.
     *
     * @param nowMs current wall-clock time in milliseconds
     */
    void addIncomplete(long nowMs) {
      timestamps[head] = nowMs;
      head = (head + 1) % timestamps.length;
      if (size < timestamps.length) {
        size++;
      }
    }

    /**
     * Returns the number of incomplete handshakes that fall within the 60-second window ending
     * at {@code nowMs}.
     *
     * @param nowMs current wall-clock time in milliseconds
     * @return count of recent incomplete handshakes
     */
    int incompleteCount(long nowMs) {
      int count = 0;
      for (int i = 0; i < size; i++) {
        int idx = (head - 1 - i + timestamps.length) % timestamps.length;
        if (nowMs - timestamps[idx] <= WINDOW_MS) {
          count++;
        }
      }
      return count;
    }
  }
}
