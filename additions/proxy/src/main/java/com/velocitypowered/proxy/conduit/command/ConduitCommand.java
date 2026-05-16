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

package com.velocitypowered.proxy.conduit.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.conduit.Conduit;
import java.net.InetAddress;
import java.net.UnknownHostException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@code /conduit} admin command. Surfaces the diagnostics, health, reload, and cache-control
 * methods that already exist on Conduit subsystems but had no operator-facing trigger.
 *
 * <h3>Subcommands</h3>
 * <ul>
 *   <li>{@code /conduit} — short usage summary.</li>
 *   <li>{@code /conduit reload} — re-reads {@code conduit.toml} and applies live values.</li>
 *   <li>{@code /conduit diagnostics} — prints the diagnostics counter snapshot.</li>
 *   <li>{@code /conduit health} — prints the backend health summary.</li>
 *   <li>{@code /conduit unblock <ip>} — clears a {@code BotFilter} block on the given IP.</li>
 *   <li>{@code /conduit cache invalidate <ip>} — drops cached MOTD and handshake entries for
 *       the given IP.</li>
 * </ul>
 *
 * <p>Permission required for every subcommand: {@value #PERMISSION}.
 */
public final class ConduitCommand {

  /** Permission required to run any {@code /conduit} subcommand. */
  public static final String PERMISSION = "conduit.admin";

  private static final Logger logger = LogManager.getLogger(ConduitCommand.class);

  private ConduitCommand() {}

  /** Registers {@code /conduit} on the proxy command manager. */
  public static void register(ProxyServer proxy, Object plugin) {
    BrigadierCommand command = new BrigadierCommand(buildNode());
    proxy.getCommandManager().register(
        proxy.getCommandManager().metaBuilder("conduit")
            .plugin(plugin)
            .build(),
        command);
    logger.info("[Conduit] Registered /conduit admin command.");
  }

  private static LiteralCommandNode<CommandSource> buildNode() {
    return BrigadierCommand.literalArgumentBuilder("conduit")
        .requires(source -> source.hasPermission(PERMISSION))
        .executes(ConduitCommand::showUsage)
        .then(BrigadierCommand.literalArgumentBuilder("reload")
            .executes(ConduitCommand::reload))
        .then(BrigadierCommand.literalArgumentBuilder("diagnostics")
            .executes(ConduitCommand::diagnostics))
        .then(BrigadierCommand.literalArgumentBuilder("health")
            .executes(ConduitCommand::health))
        .then(BrigadierCommand.literalArgumentBuilder("unblock")
            .then(BrigadierCommand.requiredArgumentBuilder("ip", StringArgumentType.string())
                .executes(ConduitCommand::unblock)))
        .then(BrigadierCommand.literalArgumentBuilder("cache")
            .then(BrigadierCommand.literalArgumentBuilder("invalidate")
                .then(BrigadierCommand.requiredArgumentBuilder("ip", StringArgumentType.string())
                    .executes(ConduitCommand::cacheInvalidate))))
        .build();
  }

  private static int showUsage(CommandContext<CommandSource> ctx) {
    CommandSource source = ctx.getSource();
    source.sendMessage(Component.text("Conduit v" + Conduit.get().getConduitVersion(),
        NamedTextColor.GOLD));
    source.sendMessage(Component.text("/conduit reload                   "
        + "— re-read conduit.toml", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit diagnostics              "
        + "— show counter snapshot", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit health                   "
        + "— show backend health", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit unblock <ip>             "
        + "— clear a bot-filter block", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit cache invalidate <ip>    "
        + "— drop MOTD + handshake cache entries", NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static int reload(CommandContext<CommandSource> ctx) {
    try {
      Conduit.get().reload();
      ctx.getSource().sendMessage(Component.text(
          "Conduit configuration reloaded.", NamedTextColor.GREEN));
      return Command.SINGLE_SUCCESS;
    } catch (RuntimeException ex) {
      ctx.getSource().sendMessage(Component.text(
          "Reload failed: " + ex.getMessage(), NamedTextColor.RED));
      logger.warn("[Conduit] /conduit reload failed", ex);
      return 0;
    }
  }

  private static int diagnostics(CommandContext<CommandSource> ctx) {
    ctx.getSource().sendMessage(Component.text(
        Conduit.get().getDiagnostics().buildSummary(), NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int health(CommandContext<CommandSource> ctx) {
    ctx.getSource().sendMessage(Component.text(
        Conduit.get().getHealthChecker().getHealthSummary(), NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int unblock(CommandContext<CommandSource> ctx) {
    String ipArg = StringArgumentType.getString(ctx, "ip");
    InetAddress addr = parseIp(ctx, ipArg);
    if (addr == null) {
      return 0;
    }
    boolean removed = Conduit.get().getBotFilter().unblock(addr);
    if (removed) {
      ctx.getSource().sendMessage(Component.text(
          "Unblocked " + addr.getHostAddress(), NamedTextColor.GREEN));
      return Command.SINGLE_SUCCESS;
    }
    ctx.getSource().sendMessage(Component.text(
        addr.getHostAddress() + " was not blocked.", NamedTextColor.YELLOW));
    return 0;
  }

  private static int cacheInvalidate(CommandContext<CommandSource> ctx) {
    String ipArg = StringArgumentType.getString(ctx, "ip");
    InetAddress addr = parseIp(ctx, ipArg);
    if (addr == null) {
      return 0;
    }
    boolean motdRemoved = Conduit.get().getMotdCache().invalidate(addr);
    Conduit.get().getHandshakeCache().invalidate(addr);
    String message = motdRemoved
        ? "Dropped MOTD + handshake cache entries for " + addr.getHostAddress()
        : "Dropped handshake cache entries for " + addr.getHostAddress()
            + " (no MOTD cache entry was present)";
    ctx.getSource().sendMessage(Component.text(message, NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  /** Parses an IP string or sends an error message and returns {@code null} on failure. */
  private static InetAddress parseIp(CommandContext<CommandSource> ctx, String raw) {
    try {
      return InetAddress.getByName(raw);
    } catch (UnknownHostException ex) {
      ctx.getSource().sendMessage(Component.text(
          "Invalid IP address: " + raw, NamedTextColor.RED));
      return null;
    }
  }
}
