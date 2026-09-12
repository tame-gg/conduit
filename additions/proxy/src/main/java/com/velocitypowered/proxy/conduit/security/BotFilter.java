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

import com.velocitypowered.proxy.conduit.network.ConnectionThrottler;
import com.velocitypowered.proxy.conduit.network.SubnetKey;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
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
 * <p>Each source network is tracked with a sliding 60-second window of incomplete-handshake
 * counts. If a single source exceeds the configured threshold within that window, all subsequent
 * connections from it are rejected at the channel-init level. Sources are grouped by
 * {@link SubnetKey} for the same reason the {@link ConnectionThrottler} groups them: an IPv6
 * attacker otherwise gets a fresh counter for every connection.
 *
 * <h3>Per-connection accounting</h3>
 * A handshake is a property of a <em>channel</em>, not of an address: one source can have many
 * connections in flight at once, which is precisely what a flood looks like. Callers therefore open
 * an {@link Attempt} per channel with {@link #beginHandshake(InetAddress)} and hand that same
 * object back to {@link #completeHandshake(Attempt)} or {@link #timeoutHandshake(Attempt)}. Keeping
 * the pending state per connection is what makes the counter fire under a flood — a single shared
 * per-address slot is overwritten by each new connection, so every timeout measures the newest
 * connection's age and counts nothing — and it stops one player's successful login from clearing
 * the pending state of an unrelated connection from the same address.
 *
 * <p>The internal map is LRU-bounded at 8 192 entries to prevent unbounded growth during an
 * attack. The lock is coarse-grained and used only on the connection-accept path, not on the
 * hot packet path.
 *
 * <p>Blocks are not permanent: once a source is blocked, the block expires after
 * {@value #BLOCK_DURATION_MS} ms of inactivity (no further incomplete handshakes within the
 * 60-second window).  This allows legitimate clients on shared IPs (carrier-grade NAT, VPN exit
 * nodes) to recover without manual intervention.
 *
 * <p>Filtering can be switched off — and back on — at runtime via {@link #setEnabled(boolean)}, so
 * {@code /conduit reload} can apply a change to {@code bot-filter-enabled} without a restart.
 */
public class BotFilter {

  /**
   * Channel attribute holding the {@link Attempt} opened for that connection, so the session
   * handler can settle it without having to thread the handle through the pipeline.
   */
  public static final AttributeKey<Attempt> ATTEMPT_ATTRIBUTE =
      AttributeKey.valueOf("conduit:bot-filter-attempt");

  private static final Logger logger = LogManager.getLogger(BotFilter.class);
  private static final int MAX_TRACKED_SOURCES = 8192;
  private static final long WINDOW_MS = 60_000L;
  /** How long a source remains blocked after the most recent incomplete handshake. */
  static final long BLOCK_DURATION_MS = 10 * 60_000L; // 10 minutes

  private volatile boolean enabled;
  private volatile long handshakeTimeoutMs;
  private volatile int threshold;
  private volatile int ipv4Prefix = ConnectionThrottler.DEFAULT_IPV4_PREFIX;
  private volatile int ipv6Prefix = ConnectionThrottler.DEFAULT_IPV6_PREFIX;
  private final ReentrantLock lock = new ReentrantLock();

  private final LinkedHashMap<SubnetKey, SourceRecord> records =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SubnetKey, SourceRecord> eldest) {
          return size() > MAX_TRACKED_SOURCES;
        }
      };

  /**
   * Constructs an enabled {@code BotFilter} with the given timeout and block threshold.
   *
   * @param handshakeTimeoutMs connections that do not complete a handshake within this many
   *                           milliseconds are counted as incomplete
   * @param threshold          the number of incomplete handshakes within a 60-second window that
   *                           triggers a block on the offending source
   */
  public BotFilter(long handshakeTimeoutMs, int threshold) {
    this(handshakeTimeoutMs, threshold, true);
  }

  /**
   * Constructs a {@code BotFilter} in the given initial state.
   *
   * @param handshakeTimeoutMs the incomplete-handshake timeout in milliseconds
   * @param threshold          the incomplete-handshake block threshold
   * @param enabled            whether filtering is active; a disabled filter tracks nothing and
   *                           reports every address as unblocked
   */
  public BotFilter(long handshakeTimeoutMs, int threshold, boolean enabled) {
    this.handshakeTimeoutMs = handshakeTimeoutMs;
    this.threshold = threshold;
    this.enabled = enabled;
  }

  /**
   * Opens tracking for one new connection from {@code addr}.
   *
   * <p>This should be called when the TCP channel is initialised, before any data is read. The
   * returned handle must be passed to {@link #completeHandshake(Attempt)} when the client sends
   * Login Start, and to {@link #timeoutHandshake(Attempt)} when the handshake timer fires.
   *
   * @param addr the remote IP address
   * @return a per-connection handle, or {@code null} when filtering is disabled
   */
  public Attempt beginHandshake(InetAddress addr) {
    if (!enabled) {
      return null;
    }
    return new Attempt(addr, System.currentTimeMillis());
  }

  /**
   * Records that the handshake for {@code attempt} completed successfully (Login Start received),
   * so it is never counted as incomplete. Accepts {@code null} for convenience at call sites where
   * filtering may be disabled.
   */
  public void completeHandshake(Attempt attempt) {
    if (attempt != null) {
      attempt.completed = true;
    }
  }

  /**
   * Settles the attempt attached to {@code channel}, if any.
   *
   * <p>Called as soon as a connection speaks Minecraft at all — a handshake packet or a legacy
   * ping, whichever state it is headed for. Waiting for Login Start instead would count every
   * ordinary server-list ping as an incomplete handshake, which is what the bot filter exists to
   * punish; a ping is a complete, legitimate transaction. What stays uncounted is a channel that
   * opens and then says nothing at all.
   */
  public void completeHandshake(Channel channel) {
    if (!enabled || channel == null) {
      return;
    }
    completeHandshake(channel.attr(ATTEMPT_ATTRIBUTE).get());
  }

  /**
   * Records that the handshake for {@code attempt} timed out (no Login Start received within the
   * configured timeout). Increments the source's incomplete counter and may trigger a block.
   * Attempts that already completed, or that have not yet reached the timeout, are ignored, and
   * each attempt can only ever be counted once.
   */
  public void timeoutHandshake(Attempt attempt) {
    if (attempt == null || !enabled || attempt.completed || attempt.counted) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - attempt.startedAt < handshakeTimeoutMs) {
      return;
    }
    attempt.counted = true;
    SubnetKey key = SubnetKey.of(attempt.address, ipv4Prefix, ipv6Prefix);
    int currentThreshold = threshold;
    boolean blockedNow = false;
    lock.lock();
    try {
      SourceRecord rec = records.computeIfAbsent(key, k -> new SourceRecord());
      rec.addIncomplete(now);
      rec.lastIncompleteAt = now;
      if (rec.incompleteCount(now) >= currentThreshold && !rec.blocked) {
        rec.blocked = true;
        blockedNow = true;
      }
    } finally {
      lock.unlock();
    }
    if (blockedNow) {
      logger.warn("[Conduit] BotFilter: blocking {} — {} incomplete handshakes in {}s window"
              + " (expires in {}m).",
          key, currentThreshold, WINDOW_MS / 1000, BLOCK_DURATION_MS / 60_000);
    }
  }

  /**
   * Returns {@code true} if the given IP address belongs to a source network that is currently
   * blocked for exceeding the incomplete-handshake threshold.
   *
   * @param addr the remote IP address to check
   * @return {@code true} if this connection should be rejected at channel-init time
   */
  public boolean isBlocked(InetAddress addr) {
    if (!enabled) {
      return false;
    }
    SubnetKey key = SubnetKey.of(addr, ipv4Prefix, ipv6Prefix);
    long now = System.currentTimeMillis();
    boolean expired = false;
    boolean blocked;
    lock.lock();
    try {
      SourceRecord rec = records.get(key);
      if (rec == null || !rec.blocked) {
        return false;
      }
      if (now - rec.lastIncompleteAt >= BLOCK_DURATION_MS) {
        rec.blocked = false;
        expired = true;
        blocked = false;
      } else {
        blocked = true;
      }
    } finally {
      lock.unlock();
    }
    if (expired) {
      logger.info("[Conduit] BotFilter: unblocking {} (block expired after {}m of inactivity).",
          key, BLOCK_DURATION_MS / 60_000);
    }
    return blocked;
  }

  /**
   * Manually unblocks the source network covering the given IP. Intended for admin commands;
   * returns {@code true} if it was actually blocked.
   */
  public boolean unblock(InetAddress addr) {
    SubnetKey key = SubnetKey.of(addr, ipv4Prefix, ipv6Prefix);
    lock.lock();
    try {
      SourceRecord rec = records.get(key);
      if (rec == null || !rec.blocked) {
        return false;
      }
      rec.blocked = false;
    } finally {
      lock.unlock();
    }
    logger.info("[Conduit] BotFilter: manually unblocking {}.", key);
    return true;
  }

  /** Returns whether bot filtering is currently active. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Turns bot filtering on or off at runtime. Tracking state is cleared when it is turned off. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (!enabled) {
      reset();
    }
  }

  /** Returns the configured handshake timeout in milliseconds. */
  public long getHandshakeTimeoutMs() {
    return handshakeTimeoutMs;
  }

  /** Updates the handshake timeout applied to connections opened from now on. */
  public void setHandshakeTimeoutMs(long handshakeTimeoutMs) {
    this.handshakeTimeoutMs = handshakeTimeoutMs;
  }

  /** Returns the current incomplete-handshake block threshold. */
  public int getThreshold() {
    return threshold;
  }

  /** Updates the incomplete-handshake block threshold for newly recorded timeouts. */
  public void setThreshold(int threshold) {
    this.threshold = threshold;
  }

  /**
   * Sets the prefix lengths used to group source addresses. Changing them clears tracking state,
   * because existing keys were masked with the previous prefixes.
   */
  public void setPrefixes(int ipv4Prefix, int ipv6Prefix) {
    if (this.ipv4Prefix == ipv4Prefix && this.ipv6Prefix == ipv6Prefix) {
      return;
    }
    this.ipv4Prefix = ipv4Prefix;
    this.ipv6Prefix = ipv6Prefix;
    reset();
  }

  /** Clears all tracking and block state. */
  public void reset() {
    lock.lock();
    try {
      records.clear();
    } finally {
      lock.unlock();
    }
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * Per-connection handshake handle. Mutated only from that connection's own event loop (the
   * channel-init thread and the timer scheduled on it), so plain fields are sufficient.
   */
  public static final class Attempt {

    private final InetAddress address;
    private final long startedAt;
    private boolean completed;
    private boolean counted;

    private Attempt(InetAddress address, long startedAt) {
      this.address = address;
      this.startedAt = startedAt;
    }

    /** Returns the address this connection came from. */
    public InetAddress address() {
      return address;
    }
  }

  /**
   * Per-source tracking record: a circular timestamp buffer of incomplete events within the
   * sliding window, and a blocked flag.
   */
  private static final class SourceRecord {

    /** Whether this source is currently blocked. Time-bounded by {@link #BLOCK_DURATION_MS}. */
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
