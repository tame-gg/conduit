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

package com.velocitypowered.proxy.protocol.packet.config;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.conduit.ConduitConfig;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import io.netty.buffer.ByteBuf;
import java.util.List;

public class KnownPacksPacket implements MinecraftPacket {

  static volatile int maxKnownPacks = resolveLimit();

  private static final QuietDecoderException TOO_MANY_PACKS = new QuietDecoderException("too many known packs");

  private List<KnownPack> packs;

  public static void setMaxKnownPacks(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("max-known-packs must be positive, got: " + limit);
    }
    maxKnownPacks = limit;
  }

  public static int getMaxKnownPacks() {
    return maxKnownPacks;
  }

  private static int resolveLimit() {
    int sysProp = Integer.getInteger("velocity.max-known-packs", -1);
    if (sysProp > 0) {
      return sysProp;
    }
    return ConduitConfig.DEFAULT_MAX_KNOWN_PACKS;
  }

  @Override
  public void decode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion protocolVersion) {
    int packCount = ProtocolUtils.readVarInt(buf);
    if (direction == ProtocolUtils.Direction.SERVERBOUND && packCount > maxKnownPacks) {
      throw TOO_MANY_PACKS;
    }

    List<KnownPack> packs = ProtocolUtils.newList(packCount);

    for (int i = 0; i < packCount; i++) {
      packs.add(KnownPack.read(buf));
    }

    this.packs = packs;
  }

  @Override
  public void encode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion protocolVersion) {
    ProtocolUtils.writeVarInt(buf, packs.size());

    for (KnownPack pack : packs) {
      pack.write(buf);
    }
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    return handler.handle(this);
  }

  public List<KnownPack> getPacks() {
    return packs;
  }

  public record KnownPack(String namespace, String id, String version) {
    private static KnownPack read(ByteBuf buf) {
      return new KnownPack(ProtocolUtils.readString(buf), ProtocolUtils.readString(buf), ProtocolUtils.readString(buf));
    }

    private void write(ByteBuf buf) {
      ProtocolUtils.writeString(buf, namespace);
      ProtocolUtils.writeString(buf, id);
      ProtocolUtils.writeString(buf, version);
    }
  }
}
