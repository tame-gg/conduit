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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandForwarderTest {

  private static final String CHANNEL = "velocity_command_forward:main";

  private static byte[] payload(String uuid, String command, boolean filtered, String log) {
    ByteArrayDataOutput out = ByteStreams.newDataOutput();
    out.writeUTF(uuid);
    out.writeUTF(command);
    out.writeByte(filtered ? 0x01 : 0x00);
    out.writeUTF(log);
    return out.toByteArray();
  }

  private static PluginMessageEvent event(ChannelIdentifier id, ChannelMessageSource source,
      byte[] data) {
    PluginMessageEvent event = mock(PluginMessageEvent.class);
    when(event.getIdentifier()).thenReturn(id);
    when(event.getSource()).thenReturn(source);
    when(event.getData()).thenReturn(data);
    return event;
  }

  /** A backend connection that reports the given server name. */
  private static ServerConnection backend(String name) {
    ServerConnection source = mock(ServerConnection.class);
    when(source.getServerInfo())
        .thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25566)));
    return source;
  }

  private ProxyServer proxyWith(CommandManager commands) {
    ProxyServer proxy = mock(ProxyServer.class);
    when(proxy.getChannelRegistrar()).thenReturn(mock(ChannelRegistrar.class));
    when(proxy.getEventManager()).thenReturn(mock(EventManager.class));
    when(proxy.getCommandManager()).thenReturn(commands);
    return proxy;
  }

  @Test
  void rejectsMalformedChannelString() {
    assertThrows(IllegalArgumentException.class,
        () -> new CommandForwarder("no-colon", false, true));
    assertThrows(IllegalArgumentException.class,
        () -> new CommandForwarder("namespace:", false, true));
  }

  @Test
  void executesConsoleCommandWhenUuidEmpty() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    ConsoleCommandSource console = mock(ConsoleCommandSource.class);
    when(proxy.getConsoleCommandSource()).thenReturn(console);

    CommandForwarder forwarder = new CommandForwarder(CHANNEL, false, true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    ServerConnection source = backend("lobby");
    forwarder.onPluginMessage(event(id, source, payload("", "alert hi", false, "log line")));

    verify(commands).executeAsync(console, "alert hi");
  }

  @Test
  void executesPlayerCommandWhenUuidPresent() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    UUID uuid = UUID.randomUUID();
    Player player = mock(Player.class);
    doReturn(Optional.of(player)).when(proxy).getPlayer(uuid);

    CommandForwarder forwarder = new CommandForwarder(CHANNEL, false, true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    ServerConnection source = backend("lobby");
    forwarder.onPluginMessage(
        event(id, source, payload(uuid.toString(), "spawn", false, "log")));

    verify(commands).executeAsync(player, "spawn");
  }

  @Test
  void ignoresMessagesOnOtherChannels() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    CommandForwarder forwarder = new CommandForwarder(CHANNEL, false, true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier other = MinecraftChannelIdentifier.create("some", "channel");
    ServerConnection source = backend("lobby");
    forwarder.onPluginMessage(event(other, source, payload("", "op me", false, "")));

    verify(commands, never()).executeAsync(any(), any());
  }

  @Test
  void ignoresNonServerSources() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    CommandForwarder forwarder = new CommandForwarder(CHANNEL, false, true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    // A Player source is not a ServerConnection — must be ignored so clients cannot self-execute.
    Player playerSource = mock(Player.class);
    forwarder.onPluginMessage(event(id, playerSource, payload("", "op me", false, "")));

    verify(commands, never()).executeAsync(any(), any());
  }

  @Test
  void blocksPlayerCommandWhenRequirePermissionAndPlayerLacksNode() {
    // require-permission gates forwarded player commands on conduit.forward.execute so an
    // unauthorised player (e.g. an alt with no LuckPerms grants) cannot forward proxy commands.
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    UUID uuid = UUID.randomUUID();
    Player player = mock(Player.class);
    when(player.hasPermission(CommandForwarder.EXECUTE_PERMISSION)).thenReturn(false);
    doReturn(Optional.of(player)).when(proxy).getPlayer(uuid);

    CommandForwarder forwarder = new CommandForwarder(CHANNEL, true, true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    ServerConnection source = backend("lobby");
    forwarder.onPluginMessage(
        event(id, source, payload(uuid.toString(), "sparkv", false, "log")));

    verify(commands, never()).executeAsync(any(), any());
  }

  @Test
  void runsPlayerCommandWhenRequirePermissionAndPlayerHasNode() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    UUID uuid = UUID.randomUUID();
    Player player = mock(Player.class);
    when(player.hasPermission(CommandForwarder.EXECUTE_PERMISSION)).thenReturn(true);
    doReturn(Optional.of(player)).when(proxy).getPlayer(uuid);

    CommandForwarder forwarder = new CommandForwarder(CHANNEL, true, true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    ServerConnection source = backend("lobby");
    forwarder.onPluginMessage(
        event(id, source, payload(uuid.toString(), "stop", false, "log")));

    verify(commands).executeAsync(player, "stop");
  }

  @Test
  void refusesForwardingFromServersOutsideTheAllowList() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    ConsoleCommandSource console = mock(ConsoleCommandSource.class);
    when(proxy.getConsoleCommandSource()).thenReturn(console);

    CommandForwarder forwarder = new CommandForwarder(CHANNEL, false, true,
        List.of("lobby"), List.of(), List.of(), true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    forwarder.onPluginMessage(
        event(id, backend("untrusted-minigames"), payload("", "stop", false, "log")));
    verify(commands, never()).executeAsync(any(), any());

    forwarder.onPluginMessage(event(id, backend("lobby"), payload("", "stop", false, "log")));
    verify(commands).executeAsync(console, "stop");
  }

  @Test
  void appliesCommandAllowAndDenyLists() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    ConsoleCommandSource console = mock(ConsoleCommandSource.class);
    when(proxy.getConsoleCommandSource()).thenReturn(console);

    CommandForwarder forwarder = new CommandForwarder(CHANNEL, false, true,
        List.of(), List.of("alert", "send"), List.of("send"), true);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    // Not on the allow-list.
    forwarder.onPluginMessage(
        event(id, backend("lobby"), payload("", "stop", false, "log")));
    // On the allow-list, but also deny-listed — deny wins.
    forwarder.onPluginMessage(
        event(id, backend("lobby"), payload("", "send player lobby", false, "log")));
    verify(commands, never()).executeAsync(any(), any());

    // Allowed: matching is on the root word, so arguments do not matter.
    forwarder.onPluginMessage(
        event(id, backend("lobby"), payload("", "alert hello there", false, "log")));
    verify(commands).executeAsync(console, "alert hello there");
  }

  @Test
  void disabledForwarderIgnoresMessagesUntilSwitchedOn() {
    CommandManager commands = mock(CommandManager.class);
    ProxyServer proxy = proxyWith(commands);
    ConsoleCommandSource console = mock(ConsoleCommandSource.class);
    when(proxy.getConsoleCommandSource()).thenReturn(console);

    CommandForwarder forwarder = new CommandForwarder(CHANNEL, false, true,
        List.of(), List.of(), List.of(), false);
    forwarder.register(new Object(), proxy);

    ChannelIdentifier id = MinecraftChannelIdentifier.create("velocity_command_forward", "main");
    forwarder.onPluginMessage(event(id, backend("lobby"), payload("", "stop", false, "log")));
    verify(commands, never()).executeAsync(any(), any());

    forwarder.setEnabled(true);
    forwarder.onPluginMessage(event(id, backend("lobby"), payload("", "stop", false, "log")));
    verify(commands).executeAsync(console, "stop");
  }
}
