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

package com.velocitypowered.proxy.conduit.forward;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Executes commands that backend servers forward to the proxy over a plugin-messaging channel.
 *
 * <p>This is a native, opt-in re-implementation of the proxy half of the
 * <a href="https://github.com/ItsTauTvyDas/VelocityCommandForward">VelocityCommandForward</a>
 * plugin, so operators no longer have to install a separate Velocity plugin for it. The backend
 * (Paper/Spigot) half of that plugin is unchanged and still required — Conduit only replaces the
 * proxy-side listener.
 *
 * <h3>Wire format</h3>
 * The message payload, written by the backend plugin with Guava's {@code ByteArrayDataOutput}, is:
 * <ol>
 *   <li>{@code UTF} — the sender UUID, or the empty string for a console-originated command;</li>
 *   <li>{@code UTF} — the command line to execute (without a leading slash);</li>
 *   <li>{@code byte} — flag bits; bit {@code 0x01} marks the command as "filtered" (silent);</li>
 *   <li>{@code UTF} — a human-readable log line the backend supplies.</li>
 * </ol>
 * This layout is kept byte-for-byte compatible with the upstream plugin so existing backend
 * installations keep working after switching to Conduit's built-in forwarder.
 *
 * <h3>Execution model</h3>
 * <ul>
 *   <li>An empty UUID runs the command as the {@linkplain ProxyServer#getConsoleCommandSource()
 *       proxy console}.</li>
 *   <li>A non-empty UUID runs the command as that player, if they are still online.</li>
 * </ul>
 * Commands are dispatched through the command manager's {@code executeAsync} so the netty thread
 * is never blocked.
 *
 * <h3>Security</h3>
 * Because a forwarded command executes with the authority of the console or a player, only
 * messages that genuinely originate from a backend server ({@link ServerConnection}) are honoured.
 * When {@code require-permission} is enabled, player-context commands additionally require the
 * player to hold {@link #EXECUTE_PERMISSION}.
 *
 * <p>A console-context command carries the proxy console's full authority, and "it came from a
 * backend" is only as strong as the weakest backend on the network — a community-run minigame box
 * is not the same trust level as your own lobby. Two further gates narrow that:
 * <ul>
 *   <li>{@code allowed-servers} — when non-empty, only those backends may forward anything at
 *       all;</li>
 *   <li>{@code command-allowlist} / {@code command-denylist} — matched against the root command
 *       word, so a backend can be limited to the handful of proxy commands it actually needs.</li>
 * </ul>
 *
 * <p>Forwarding can be switched off — and back on — at runtime via {@link #setEnabled(boolean)}.
 */
public class CommandForwarder {

  /**
   * Permission a player must hold for a player-context forwarded command when {@code
   * require-permission} is enabled. Note: Velocity has no permission registry, so this node will not
   * autocomplete in the LuckPerms web editor — grant it explicitly with
   * {@code /lpv user <name> permission set conduit.forward.execute true} (or to a group).
   */
  public static final String EXECUTE_PERMISSION = "conduit.forward.execute";

  /** Filter flag: the backend marked this command as silent (no log / feedback). */
  private static final int FLAG_FILTERED = 0x01;

  /** Upper bound on a backend-supplied log line echoed to the console. */
  private static final int MAX_LOG_LINE_LENGTH = 512;

  private static final Logger logger = LogManager.getLogger(CommandForwarder.class);

  private final String channelName;
  private final ChannelIdentifier channel;
  private volatile boolean enabled;
  private volatile boolean requirePermission;
  private volatile boolean logForwardedCommands;
  private volatile Set<String> allowedServers;
  private volatile Set<String> commandAllowlist;
  private volatile Set<String> commandDenylist;
  private volatile ProxyServer proxy;
  private volatile boolean channelRegistered;

  /**
   * Constructs an enabled {@code CommandForwarder} with no server or command restrictions.
   *
   * @param channelName          the plugin-messaging channel the backend sends on
   *                             ({@code namespace:path}); must match the backend plugin
   * @param requirePermission    whether player-context commands require {@link #EXECUTE_PERMISSION}
   * @param logForwardedCommands whether the backend-supplied log line is echoed to the console
   */
  public CommandForwarder(String channelName, boolean requirePermission,
      boolean logForwardedCommands) {
    this(channelName, requirePermission, logForwardedCommands, List.of(), List.of(), List.of(),
        true);
  }

  /**
   * Constructs a {@code CommandForwarder}.
   *
   * @param channelName          the plugin-messaging channel the backend sends on
   * @param requirePermission    whether player-context commands require {@link #EXECUTE_PERMISSION}
   * @param logForwardedCommands whether the backend-supplied log line is echoed to the console
   * @param allowedServers       backends permitted to forward; empty means every backend
   * @param commandAllowlist     root command words permitted; empty means every command
   * @param commandDenylist      root command words always refused; takes precedence over the
   *                             allow-list
   * @param enabled              whether forwarding is active
   */
  public CommandForwarder(String channelName, boolean requirePermission,
      boolean logForwardedCommands, List<String> allowedServers, List<String> commandAllowlist,
      List<String> commandDenylist, boolean enabled) {
    this.channelName = channelName;
    this.channel = parseChannel(channelName);
    this.requirePermission = requirePermission;
    this.logForwardedCommands = logForwardedCommands;
    this.allowedServers = lowerCaseSet(allowedServers);
    this.commandAllowlist = lowerCaseSet(commandAllowlist);
    this.commandDenylist = lowerCaseSet(commandDenylist);
    this.enabled = enabled;
  }

  private static Set<String> lowerCaseSet(List<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new HashSet<>(values.size());
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        out.add(value.trim().toLowerCase(Locale.ROOT));
      }
    }
    return Set.copyOf(out);
  }

  private static ChannelIdentifier parseChannel(String id) {
    int colon = id == null ? -1 : id.indexOf(':');
    if (colon <= 0 || colon == id.length() - 1) {
      throw new IllegalArgumentException(
          "conduit.toml: forwarding.channel must be 'namespace:path', got '" + id + "'");
    }
    return MinecraftChannelIdentifier.create(id.substring(0, colon), id.substring(colon + 1));
  }

  /**
   * Registers this listener on the given proxy, and the forwarding channel itself when forwarding
   * is enabled.
   *
   * <p>The listener is always registered so {@code /conduit reload} can switch forwarding on
   * without a restart; the channel registration follows the enabled flag, because registering a
   * plugin channel advertises it to clients and backends.
   */
  public void register(Object plugin, ProxyServer proxy) {
    this.proxy = proxy;
    proxy.getEventManager().register(plugin, this);
    if (enabled) {
      registerChannel();
      logger.info("[Conduit] Command forwarding enabled on channel '{}' (require-permission={},"
          + " {} allowed servers, {} allow-listed commands, {} deny-listed).",
          channelName, requirePermission, allowedServers.size(), commandAllowlist.size(),
          commandDenylist.size());
    }
  }

  private void registerChannel() {
    ProxyServer server = this.proxy;
    if (server != null && !channelRegistered) {
      server.getChannelRegistrar().register(channel);
      channelRegistered = true;
    }
  }

  /** Returns whether command forwarding is currently active. */
  public boolean isEnabled() {
    return enabled;
  }

  /** Turns command forwarding on or off at runtime. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (enabled) {
      registerChannel();
    }
  }

  /** Replaces the permission requirement for player-context forwarded commands. */
  public void setRequirePermission(boolean requirePermission) {
    this.requirePermission = requirePermission;
  }

  /** Replaces whether backend-supplied log lines are echoed to the console. */
  public void setLogForwardedCommands(boolean logForwardedCommands) {
    this.logForwardedCommands = logForwardedCommands;
  }

  /** Replaces the set of backends permitted to forward commands; empty means every backend. */
  public void setAllowedServers(List<String> allowedServers) {
    this.allowedServers = lowerCaseSet(allowedServers);
  }

  /** Replaces the permitted root command words; empty means every command. */
  public void setCommandAllowlist(List<String> commandAllowlist) {
    this.commandAllowlist = lowerCaseSet(commandAllowlist);
  }

  /** Replaces the always-refused root command words. */
  public void setCommandDenylist(List<String> commandDenylist) {
    this.commandDenylist = lowerCaseSet(commandDenylist);
  }

  /** Returns the backends permitted to forward commands; empty means every backend. */
  public Set<String> getAllowedServers() {
    return allowedServers;
  }

  /** Returns the permitted root command words; empty means every command. */
  public Set<String> getCommandAllowlist() {
    return commandAllowlist;
  }

  /** Returns the always-refused root command words. */
  public Set<String> getCommandDenylist() {
    return commandDenylist;
  }

  /**
   * Handles a forwarded-command message. Runs at {@link PostOrder#EARLY} and marks the event
   * {@link PluginMessageEvent.ForwardResult#handled() handled} so the payload is consumed at the
   * proxy instead of being relayed onward, exactly as the upstream plugin does.
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onPluginMessage(PluginMessageEvent event) {
    if (!enabled || !channel.equals(event.getIdentifier())) {
      return;
    }
    // The command is executed here; never relay the raw payload to another backend.
    event.setResult(PluginMessageEvent.ForwardResult.handled());

    // Only genuine backend connections may forward commands — never a player's own channel data.
    if (!(event.getSource() instanceof ServerConnection source)) {
      return;
    }
    ProxyServer proxy = this.proxy;
    if (proxy == null) {
      return;
    }

    String sourceServer = source.getServerInfo().getName();
    Set<String> allowed = allowedServers;
    if (!allowed.isEmpty() && !allowed.contains(sourceServer.toLowerCase(Locale.ROOT))) {
      logger.warn("[Conduit] Refused a forwarded command from backend '{}' — it is not in"
          + " [forwarding] allowed-servers.", sourceServer);
      return;
    }

    final String uuidRaw;
    final String command;
    final int flags;
    final String log;
    try {
      ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
      uuidRaw = in.readUTF();
      command = in.readUTF();
      flags = in.readByte();
      log = in.readUTF();
    } catch (IllegalStateException | IndexOutOfBoundsException malformed) {
      logger.warn("[Conduit] Discarded a malformed forwarded-command message from backend '{}'.",
          source.getServerInfo().getName());
      return;
    }

    if (command.isBlank()) {
      return;
    }
    if (!isCommandPermitted(command, sourceServer)) {
      return;
    }
    boolean filtered = (flags & FLAG_FILTERED) != 0;

    if (uuidRaw.isEmpty()) {
      maybeLog(filtered, log);
      proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command);
    } else {
      executeAsPlayer(proxy, uuidRaw, command, filtered, log);
    }
  }

  /**
   * Applies the command allow/deny lists to the root word of {@code command}.
   *
   * <p>Matching the root word only is deliberate: it is the part that decides which command runs,
   * and matching deeper would invite bypasses through argument shuffling rather than prevent them.
   */
  private boolean isCommandPermitted(String command, String sourceServer) {
    String root = rootWord(command);
    if (commandDenylist.contains(root)) {
      logger.warn("[Conduit] Refused forwarded command '/{}' from backend '{}' —"
          + " it is deny-listed in [forwarding] command-denylist.", root, sourceServer);
      return false;
    }
    Set<String> allowlist = commandAllowlist;
    if (!allowlist.isEmpty() && !allowlist.contains(root)) {
      logger.warn("[Conduit] Refused forwarded command '/{}' from backend '{}' —"
          + " it is not in [forwarding] command-allowlist.", root, sourceServer);
      return false;
    }
    return true;
  }

  /** Returns the lower-cased first word of a command line, without any leading slash. */
  private static String rootWord(String command) {
    String trimmed = command.strip();
    if (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    int space = trimmed.indexOf(' ');
    if (space >= 0) {
      trimmed = trimmed.substring(0, space);
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private void executeAsPlayer(ProxyServer proxy, String uuidRaw, String command,
      boolean filtered, String log) {
    final UUID uuid;
    try {
      uuid = UUID.fromString(uuidRaw);
    } catch (IllegalArgumentException badUuid) {
      logger.warn("[Conduit] Ignored forwarded command with an invalid sender UUID '{}'.", uuidRaw);
      return;
    }
    proxy.getPlayer(uuid).ifPresent(player -> {
      // Gate player-context forwarded commands: without this, any player who can invoke the backend
      // /proxyexec could run proxy commands, and relying on each command's own permission is not
      // enough (e.g. /sparkv). The node is a plain Velocity permission — LuckPerms will not
      // autocomplete it in the web editor (Velocity has no permission registry), but it can still be
      // granted explicitly, so the block message spells out exactly how.
      if (requirePermission && !player.hasPermission(EXECUTE_PERMISSION)) {
        logger.warn("[Conduit] Blocked forwarded command '/{}' from {} — missing permission '{}'. "
                + "Grant it with '/lpv user {} permission set {} true' (or to a group), or set "
                + "[forwarding] require-permission = false in conduit.toml to disable this gate.",
            command, player.getUsername(), EXECUTE_PERMISSION, player.getUsername(),
            EXECUTE_PERMISSION);
        return;
      }
      maybeLog(filtered, log);
      proxy.getCommandManager().executeAsync(player, command);
    });
  }

  private void maybeLog(boolean filtered, String log) {
    if (!filtered && logForwardedCommands && log != null && !log.isEmpty()) {
      logger.info("[Conduit] Forwarded command: {}", sanitiseLogLine(log));
    }
  }

  /**
   * Flattens a backend-supplied log line to something safe to print.
   *
   * <p>The text comes off the wire, so it is not allowed to span lines — otherwise a backend could
   * forge entries that look like they came from the proxy itself — and it is length-capped so a
   * large payload cannot be used to flood the log.
   */
  private static String sanitiseLogLine(String log) {
    String flattened = log.replace('\r', ' ').replace('\n', ' ');
    return flattened.length() <= MAX_LOG_LINE_LENGTH
        ? flattened
        : flattened.substring(0, MAX_LOG_LINE_LENGTH) + "…";
  }

  /** Returns the channel id this forwarder listens on. */
  public String getChannelName() {
    return channelName;
  }

  /** Returns whether player-context forwarded commands require {@link #EXECUTE_PERMISSION}. */
  public boolean isRequirePermission() {
    return requirePermission;
  }

  /** Returns whether backend-supplied log lines are echoed to the console. */
  public boolean isLogForwardedCommands() {
    return logForwardedCommands;
  }
}
