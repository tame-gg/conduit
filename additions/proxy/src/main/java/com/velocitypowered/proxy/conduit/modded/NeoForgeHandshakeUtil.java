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

package com.velocitypowered.proxy.conduit.modded;

import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.conduit.diagnostics.ConduitDiagnostics;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility methods for handling NeoForge / FML3 plugin-message channels during the configuration
 * and login phases.
 *
 * <p>NeoForge 1.20.4+ uses the {@code neoforge:handshake} channel (formerly {@code fml:handshake}
 * in FML2) to negotiate mod lists between client and server.  Velocity must transparently forward
 * these messages without mangling them.  This class also detects when a client or server sends a
 * malformed or oversized handshake payload so the connection can be dropped cleanly.
 */
public final class NeoForgeHandshakeUtil {

  private static final Logger logger = LogManager.getLogger(NeoForgeHandshakeUtil.class);

  /** Channel names used by NeoForge / FML across versions. */
  public static final String NEOFORGE_HANDSHAKE_CHANNEL = "neoforge:handshake";
  public static final String FML2_HANDSHAKE_CHANNEL     = "fml:handshake";
  public static final String FML_LOGIN_WRAPPER_CHANNEL  = "fml:loginwrapper";
  public static final String FORGE_REGISTER_CHANNEL     = "REGISTER";
  public static final String FORGE_UNREGISTER_CHANNEL   = "UNREGISTER";

  /** Maximum acceptable payload for a mod-handshake plugin message (4 MiB). */
  private static final int MAX_HANDSHAKE_PAYLOAD_BYTES = 4 * 1024 * 1024;

  private NeoForgeHandshakeUtil() {}

  /**
   * Returns {@code true} if the plugin message is part of a NeoForge / Forge handshake sequence
   * and should be tracked by the handshake state machine rather than blindly forwarded.
   */
  public static boolean isModHandshakeMessage(PluginMessagePacket msg) {
    String ch = msg.getChannel();
    return NEOFORGE_HANDSHAKE_CHANNEL.equals(ch)
        || FML2_HANDSHAKE_CHANNEL.equals(ch)
        || FML_LOGIN_WRAPPER_CHANNEL.equals(ch);
  }

  /**
   * Returns {@code true} if the channel is a Forge channel-registration message.
   * These must be parsed and not simply forwarded verbatim in some proxy modes.
   */
  public static boolean isChannelRegistrationMessage(PluginMessagePacket msg) {
    String ch = msg.getChannel();
    return FORGE_REGISTER_CHANNEL.equals(ch) || FORGE_UNREGISTER_CHANNEL.equals(ch);
  }

  /**
   * Validates that a plugin message from a modded client has a plausible payload size.
   * Returns {@code false} and logs a warning if the payload is suspiciously large.
   */
  public static boolean validatePayloadSize(PluginMessagePacket msg, String playerName,
      ConduitDiagnostics diagnostics) {
    int len = msg.content().readableBytes();
    if (len > MAX_HANDSHAKE_PAYLOAD_BYTES) {
      logger.warn("[Conduit] {} sent oversized mod-handshake payload on channel '{}': "
          + "{} bytes (limit {}). Dropping connection.",
          playerName, msg.getChannel(), len, MAX_HANDSHAKE_PAYLOAD_BYTES);
      diagnostics.recordOversizedPayload(playerName, msg.getChannel(), len);
      return false;
    }
    return true;
  }

  /**
   * Extracts a printable representation of the first few bytes of a plugin message payload for
   * diagnostic logging.  Never throws; returns a safe placeholder on any error.
   */
  public static String peekPayloadHex(PluginMessagePacket msg, int maxBytes) {
    try {
      int readable = Math.min(msg.content().readableBytes(), maxBytes);
      byte[] buf = new byte[readable];
      msg.content().getBytes(msg.content().readerIndex(), buf);
      StringBuilder sb = new StringBuilder(readable * 3);
      for (byte b : buf) {
        sb.append(String.format("%02X ", b));
      }
      return sb.toString().trim();
    } catch (Exception e) {
      return "<error reading payload>";
    }
  }

  /**
   * Attempts to detect whether a client connection looks like a NeoForge client based on the
   * channel list it sent in its Login Plugin Request.
   */
  public static boolean looksLikeNeoForgeClient(Iterable<String> registeredChannels) {
    for (String ch : registeredChannels) {
      if (ch.startsWith("neoforge:") || ch.startsWith("fml:")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Attempts to detect a Legacy Forge (FML1) client.  FML1 clients append {@code \0FML\0} to
   * their server-address field during the handshake.
   */
  public static boolean isLegacyForgeAddress(String address) {
    return address != null && address.contains("\0FML\0");
  }

  /**
   * Strips the FML1 marker from an address string so it can be parsed as a normal host.
   */
  public static String stripLegacyForgeMarker(String address) {
    if (address == null) {
      return null;
    }
    int idx = address.indexOf('\0');
    return idx >= 0 ? address.substring(0, idx) : address;
  }

  /**
   * Decodes the channel list from a REGISTER/UNREGISTER payload.
   * The payload is a NUL-delimited list of channel names encoded in UTF-8.
   */
  public static String[] decodeChannelList(byte[] payload) {
    String raw = new String(payload, StandardCharsets.UTF_8);
    return raw.split("\0");
  }
}
