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

package com.velocitypowered.proxy.radar.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages per-player outbound packet queues during server transitions.
 *
 * <h3>Why this exists</h3>
 * When a player switches from server A to server B, Velocity must:
 * <ol>
 *   <li>Send the "respawn / reconfigure" sequence to the client</li>
 *   <li>Establish a new connection to server B</li>
 *   <li>Complete server B's login/config phases</li>
 * </ol>
 *
 * <p>During step 2–3, server A may still be sending play packets (chunk updates, entity moves,
 * etc.)
 * that are no longer relevant.  Without queuing, these packets are either forwarded to the client
 * and confuse its state machine, or dropped, causing protocol errors.
 *
 * <p>This manager holds packets in an in-memory queue during the transition window, then either
 * discards them (the common case) or replays them if the server switch fails and the player falls
 * back to their original server.  The queue has a configurable depth cap; if exceeded, old packets
 * are dropped (they are stale anyway).
 *
 * <h3>Thread safety</h3>
 * Queues are per-player and accessed only from the player's Netty event-loop thread after the
 * queue is created, so the inner {@link Deque} does not need additional synchronisation.  The
 * outer map uses a {@link ConcurrentHashMap} because creation/removal can race with a queue lookup
 * on a different thread during cleanup.
 */
public final class PacketQueueManager {

  private static final Logger logger = LogManager.getLogger(PacketQueueManager.class);

  private volatile int maxDepth;
  private final ConcurrentHashMap<UUID, PlayerQueue> queues = new ConcurrentHashMap<>();

  /**
   * Constructs a manager with the given default queue depth cap.
   *
   * @param maxDepth maximum packets buffered per player before oldest are dropped
   */
  public PacketQueueManager(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  /** Updates the maximum queue depth; applied to newly opened queues only. */
  public void setMaxDepth(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  /**
   * Opens a packet queue for {@code playerId}.  Any packets written to the player's channel while
   * the queue is open are held here instead of being forwarded.
   */
  public void openQueue(UUID playerId) {
    queues.put(playerId, new PlayerQueue(maxDepth));
    logger.debug("[Conduit] PacketQueue opened for {}", playerId);
  }

  /**
   * Buffers a packet for later.  The {@link ByteBuf} reference count is retained by this call;
   * the manager will release it when the queue is flushed or discarded.
   *
   * @return {@code true} if the packet was queued; {@code false} if no queue is open for this
   *         player (caller must handle the packet normally)
   */
  public boolean enqueue(UUID playerId, ByteBuf packet) {
    PlayerQueue q = queues.get(playerId);
    if (q == null) {
      return false;
    }
    q.add(packet.retainedDuplicate());
    return true;
  }

  /**
   * Flushes all queued packets to the given channel and closes the queue.
   * Call this when the new server connection is ready and the client state machine is prepared.
   */
  public void flushTo(UUID playerId, Channel channel) {
    PlayerQueue q = queues.remove(playerId);
    if (q == null) {
      return;
    }
    int flushed = 0;
    ByteBuf buf;
    while ((buf = q.poll()) != null) {
      channel.write(buf);
      flushed++;
    }
    if (flushed > 0) {
      channel.flush();
      logger.debug("[Conduit] PacketQueue flushed {} packets for {}", flushed, playerId);
    }
  }

  /**
   * Discards all queued packets and closes the queue.
   * Call this when a server switch completes and the old packets are no longer needed.
   */
  public void discard(UUID playerId) {
    PlayerQueue q = queues.remove(playerId);
    if (q == null) {
      return;
    }
    int dropped = 0;
    ByteBuf buf;
    while ((buf = q.poll()) != null) {
      buf.release();
      dropped++;
    }
    if (dropped > 0) {
      logger.debug("[Conduit] PacketQueue discarded {} stale packets for {}", dropped, playerId);
    }
  }

  /**
   * Returns the number of packets currently queued for a player, or -1 if no queue is open.
   */
  public int queueDepth(UUID playerId) {
    PlayerQueue q = queues.get(playerId);
    return q == null ? -1 : q.size();
  }

  /** Cleans up all queues (called on proxy shutdown). */
  public void shutdown() {
    queues.keySet().forEach(this::discard);
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  private final class PlayerQueue {
    private final Deque<ByteBuf> deque = new ArrayDeque<>(32);
    private final int cap;

    PlayerQueue(int cap) {
      this.cap = cap;
    }

    void add(ByteBuf buf) {
      if (deque.size() >= cap) {
        // Drop the oldest packet (it is the most stale)
        ByteBuf dropped = deque.pollFirst();
        if (dropped != null) {
          dropped.release();
        }
      }
      deque.addLast(buf);
    }

    ByteBuf poll() {
      return deque.pollFirst();
    }

    int size() {
      return deque.size();
    }
  }
}
