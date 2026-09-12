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

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.conduit.diagnostics.ConduitDiagnostics;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Intercepts player-originated plugin messages on known cheat / exploit channels (e.g.,
 * World-Downloader, X-Ray clients) and applies a configured action — drop the message, kick the
 * player, or log only.
 *
 * <h3>Blocklist matching</h3>
 * Patterns are checked against the full channel id ({@code namespace:path}). A pattern ending in
 * {@code ':'} matches any channel under that namespace; otherwise the match is exact. Matching is
 * case-insensitive because some legacy mods emit mixed-case channel names.
 *
 * <h3>Action semantics</h3>
 * <ul>
 *   <li>{@code drop}: the event result is set to {@code ForwardResult.handled()} so the message
 *       is never forwarded to the destination. The connection stays open.</li>
 *   <li>{@code kick}: the message is dropped and the player is disconnected with a configurable
 *       message. Use this when the channel is unambiguously hostile (e.g., WDL).</li>
 *   <li>{@code log}: the message is forwarded normally and a one-line warning is emitted. Useful
 *       for evaluating the impact of a new blocklist entry before enforcing it.</li>
 * </ul>
 *
 * <h3>Bypass permission</h3>
 * Players with the {@code conduit.channelguard.bypass} permission are exempt from all blocks. This
 * lets staff use otherwise-blocked admin tools without weakening the protection for the rest of
 * the network.
 *
 * <p>The guard can be switched off — and back on — at runtime via {@link #setEnabled(boolean)},
 * so {@code /conduit reload} can apply a change to {@code channel-guard} without a restart.
 */
public class ChannelGuard {

  /** Action taken when a blocked channel is observed. */
  public enum Action {
    /** Silently drop the message. The connection remains open. */
    DROP,
    /** Drop the message and disconnect the player. */
    KICK,
    /** Forward the message; emit a log line only. */
    LOG;

    /** Parses a case-insensitive string; throws {@link IllegalArgumentException} on unknown. */
    public static Action parse(String name) {
      if (name == null) {
        throw new IllegalArgumentException("ChannelGuard action must not be null");
      }
      return Action.valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
  }

  private static final Logger logger = LogManager.getLogger(ChannelGuard.class);
  /** Component shown to kicked players. Operators cannot configure this yet by design. */
  private static final Component KICK_MESSAGE =
      Component.text("You are using a client mod that is not allowed on this server.");

  /** Permission key that lets staff bypass all channel-guard checks. */
  public static final String BYPASS_PERMISSION = "conduit.channelguard.bypass";

  private volatile boolean enabled;
  private volatile List<String> blockList;
  private volatile Action action;
  private final ConduitDiagnostics diagnostics;

  /**
   * Constructs an enabled {@code ChannelGuard}.
   *
   * @param blockList   the list of channel patterns to block; each is either an exact id or a
   *                    namespace prefix ending in {@code ':'}
   * @param action      the action to take when a blocked channel is observed
   * @param diagnostics the diagnostics instance used to record block events; may be {@code null}
   */
  public ChannelGuard(List<String> blockList, Action action, ConduitDiagnostics diagnostics) {
    this(blockList, action, diagnostics, true);
  }

  /**
   * Constructs a {@code ChannelGuard} in the given initial state.
   *
   * @param blockList   the list of channel patterns to block
   * @param action      the action to take when a blocked channel is observed
   * @param diagnostics the diagnostics instance used to record block events; may be {@code null}
   * @param enabled     whether the guard is active; a disabled guard inspects nothing
   */
  public ChannelGuard(List<String> blockList, Action action, ConduitDiagnostics diagnostics,
      boolean enabled) {
    this.blockList = List.copyOf(blockList);
    this.action = action;
    this.diagnostics = diagnostics;
    this.enabled = enabled;
  }

  /**
   * Registers this guard as a {@link PluginMessageEvent} listener on the given proxy.
   *
   * <p>Registered whether or not the guard is currently enabled, so {@code /conduit reload} can
   * switch it on without a restart.
   */
  public void register(Object plugin, ProxyServer proxy) {
    proxy.getEventManager().register(plugin, this);
    logger.info("[Conduit] ChannelGuard registered (enabled={}, action={}, {} patterns).",
        enabled, action, blockList.size());
  }

  /** Returns whether the channel guard is currently active. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Turns the channel guard on or off at runtime. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** Replaces the blocklist and action, e.g. after {@code /conduit reload}. */
  public void reconfigure(List<String> blockList, Action action) {
    this.blockList = List.copyOf(blockList);
    this.action = action;
  }

  /**
   * Handles a {@link PluginMessageEvent} by checking the channel id against the blocklist.
   *
   * <p>Runs at {@link PostOrder#EARLY} so blocked messages are stopped before plugin listeners see
   * them — most plugins assume their channels are well-formed and forwarding hostile data first
   * can crash them.
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onPluginMessage(PluginMessageEvent event) {
    if (!enabled || !(event.getSource() instanceof Player player)) {
      return;
    }
    if (player.hasPermission(BYPASS_PERMISSION)) {
      return;
    }
    String channelId = event.getIdentifier().getId();
    if (!isBlocked(channelId)) {
      return;
    }
    handleBlocked(player, channelId, event);
  }

  /**
   * Returns {@code true} if the given channel id matches the blocklist.
   *
   * <p>Exposed so other subsystems (e.g., a {@code /modlist} command) can flag suspicious
   * channels in their output.
   */
  public boolean isBlocked(String channelId) {
    if (!enabled) {
      return false;
    }
    String normalised = channelId.toLowerCase(Locale.ROOT);
    for (String pattern : blockList) {
      String lowerPattern = pattern.toLowerCase(Locale.ROOT);
      if (lowerPattern.endsWith(":")) {
        if (normalised.startsWith(lowerPattern)) {
          return true;
        }
      } else if (normalised.equals(lowerPattern)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the configured action. */
  public Action getAction() {
    return action;
  }

  /** Returns an unmodifiable view of the configured blocklist. */
  public List<String> getBlockList() {
    return blockList;
  }

  private void handleBlocked(Player player, String channelId, PluginMessageEvent event) {
    if (diagnostics != null) {
      diagnostics.recordChannelBlocked(player.getUsername(), channelId, action.name());
    }
    switch (action) {
      case DROP -> {
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        logger.warn("[Conduit] ChannelGuard dropped '{}' from {}.",
            channelId, player.getUsername());
      }
      case KICK -> {
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        logger.warn("[Conduit] ChannelGuard kicking {} for using '{}'.",
            player.getUsername(), channelId);
        player.disconnect(KICK_MESSAGE);
      }
      case LOG -> logger.warn("[Conduit] ChannelGuard observed blocked channel '{}' from {} "
          + "(forwarding anyway).", channelId, player.getUsername());
      default -> {
        // exhaustive
      }
    }
  }
}
