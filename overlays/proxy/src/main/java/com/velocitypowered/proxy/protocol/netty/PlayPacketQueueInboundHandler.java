/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.velocitypowered.proxy.protocol.netty;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.conduit.Conduit;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.Queue;
import org.jetbrains.annotations.NotNull;

/**
 * Queues up any pending PLAY packets while the client is in the CONFIG state.
 *
 * <p>Much of the Velocity API (i.e., chat messages) utilize PLAY packets; however, the client is
 * incapable of receiving these packets during the CONFIG state. Certain events such as the
 * ServerPreConnectEvent may be called during this time, and we need to ensure that any API that
 * uses these packets will work as expected.
 *
 * <p>This handler will queue up any packets that are sent to the client during this time, and send
 * them once the client has (re)entered the PLAY state.
 *
 * <p><b>Conduit:</b> in addition to the upstream byte-size guard, Conduit applies an optional
 * per-queue depth cap (see {@code packet-queue-max-depth} in {@code conduit.toml}) and records a
 * diagnostics counter each time the queue is flushed.
 */
public class PlayPacketQueueInboundHandler extends ChannelDuplexHandler {

  private static final int MAXIMUM_SIZE = Integer.getInteger("velocity.maximum-play-queue-size", 128 * 1024 * 1024); // 128MiB by default
  private static final QuietDecoderException QUEUE_LIMIT_FAILED = new QuietDecoderException(
      "Queue too big (greater than " + MAXIMUM_SIZE + " bytes)");

  private final StateRegistry.PacketRegistry.ProtocolRegistry registry;
  private final boolean discardStaleInbound;

  private final Queue<Object> queue = new ArrayDeque<>();
  private int queueSize = 0;

  /**
   * Provides registries for "client" &amp; server bound packets.
   *
   * @param version the protocol version
   * @param direction the direction of the packet flow (typically {@code SERVERBOUND})
   * @param discardStaleInbound whether play packets from a previous session should be discarded
   */
  public PlayPacketQueueInboundHandler(ProtocolVersion version, ProtocolUtils.Direction direction,
                                       boolean discardStaleInbound) {
    this.registry = StateRegistry.CONFIG.getProtocolRegistry(direction, version);
    this.discardStaleInbound = discardStaleInbound;
  }

  @Override
  public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg) {
    if (msg instanceof MinecraftPacket packet) {
      // If the packet exists in the CONFIG state, we want to always
      // ensure that it gets handled by the current handler
      if (this.registry.containsPacket(packet)) {
        ctx.fireChannelRead(msg);
        return;
      }
    }

    if (this.discardStaleInbound) {
      // Re-entering configuration: this play packet belongs to the previous play session and
      // must not be replayed into the next one, so drop it rather than queueing it.
      ReferenceCountUtil.release(msg);
      return;
    }

    int length = queuedLength(msg);
    if (this.queueSize + length > MAXIMUM_SIZE) {
      ReferenceCountUtil.release(msg);
      throw QUEUE_LIMIT_FAILED;
    }

    // Conduit: drop the oldest queued packet once the configured depth cap is reached, so a stalled
    // CONFIG transition cannot accumulate an unbounded number of small packets.
    if (isConduitPacketQueueEnabled()
        && this.queue.size() >= Conduit.get().getConfig().getPacketQueueMaxDepth()) {
      Object dropped = this.queue.poll();
      this.queueSize -= queuedLength(dropped);
      ReferenceCountUtil.release(dropped);
    }

    this.queueSize += length;

    // Otherwise, queue the packet
    this.queue.offer(msg);
  }

  @Override
  public void channelInactive(@NotNull ChannelHandlerContext ctx) throws Exception {
    this.releaseQueue(ctx, false);

    super.channelInactive(ctx);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    this.releaseQueue(ctx, ctx.channel().isActive());
  }

  private void releaseQueue(ChannelHandlerContext ctx, boolean active) {
    // Handle all the queued packets
    int flushed = 0;
    Object msg;
    while ((msg = this.queue.poll()) != null) {
      if (active) {
        ctx.fireChannelRead(msg);
        flushed++;
      } else {
        ReferenceCountUtil.release(msg);
      }
    }
    this.queueSize = 0;

    if (active && flushed > 0 && isConduitPacketQueueEnabled()) {
      Conduit.get().getDiagnostics().recordPacketQueueFlush("serverbound", flushed);
    }
  }

  private boolean isConduitPacketQueueEnabled() {
    try {
      return Conduit.get().getConfig().isPacketQueueOptEnabled();
    } catch (IllegalStateException ignored) {
      return false;
    }
  }

  private int queuedLength(Object msg) {
    if (msg instanceof ByteBuf) {
      return ((ByteBuf) msg).readableBytes();
    }
    if (msg instanceof ByteBufHolder) {
      return ((ByteBufHolder) msg).content().readableBytes();
    }
    return 0;
  }
}
