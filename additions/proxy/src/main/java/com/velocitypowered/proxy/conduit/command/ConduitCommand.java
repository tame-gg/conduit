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
import com.velocitypowered.proxy.conduit.ConduitConfig;
import com.velocitypowered.proxy.conduit.diagnostics.ConduitConfigDiff;
import com.velocitypowered.proxy.conduit.diagnostics.ConduitDoctor;
import com.velocitypowered.proxy.conduit.diagnostics.ConduitMetricsSnapshot;
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
    BrigadierCommand command = new BrigadierCommand(buildNode(proxy));
    proxy.getCommandManager().register(
        proxy.getCommandManager().metaBuilder("conduit")
            .plugin(plugin)
            .build(),
        command);
    logger.info("[Conduit] Registered /conduit admin command.");
  }

  private static LiteralCommandNode<CommandSource> buildNode(ProxyServer proxy) {
    return BrigadierCommand.literalArgumentBuilder("conduit")
        .requires(source -> source.hasPermission(PERMISSION))
        .executes(ConduitCommand::showUsage)
        .then(BrigadierCommand.literalArgumentBuilder("reload")
            .executes(ConduitCommand::reload))
        .then(BrigadierCommand.literalArgumentBuilder("diagnostics")
            .executes(ConduitCommand::diagnostics))
        .then(BrigadierCommand.literalArgumentBuilder("health")
            .executes(ConduitCommand::health))
        .then(BrigadierCommand.literalArgumentBuilder("doctor")
            .executes(ctx -> doctor(ctx, proxy)))
        .then(BrigadierCommand.literalArgumentBuilder("metrics")
            .then(BrigadierCommand.literalArgumentBuilder("json")
                .executes(ConduitCommand::metricsJson)))
        .then(BrigadierCommand.literalArgumentBuilder("attackmode")
            .then(BrigadierCommand.literalArgumentBuilder("on")
                .executes(ConduitCommand::attackModeOn))
            .then(BrigadierCommand.literalArgumentBuilder("off")
                .executes(ConduitCommand::attackModeOff))
            .then(BrigadierCommand.literalArgumentBuilder("status")
                .executes(ConduitCommand::attackModeStatus)))
        .then(BrigadierCommand.literalArgumentBuilder("maintenance")
            .then(BrigadierCommand.literalArgumentBuilder("on")
                .executes(ctx -> maintenance(ctx, true)))
            .then(BrigadierCommand.literalArgumentBuilder("off")
                .executes(ctx -> maintenance(ctx, false)))
            .then(BrigadierCommand.literalArgumentBuilder("status")
                .executes(ConduitCommand::maintenanceStatus)))
        .then(BrigadierCommand.literalArgumentBuilder("config")
            .then(BrigadierCommand.literalArgumentBuilder("diff")
                .executes(ConduitCommand::configDiff)))
        .then(BrigadierCommand.literalArgumentBuilder("failover")
            .then(BrigadierCommand.literalArgumentBuilder("test")
                .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.string())
                    .executes(ConduitCommand::failoverTest))))
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
    source.sendMessage(Component.text("/conduit doctor                   "
        + "— check config and feature wiring", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit metrics json             "
        + "— show diagnostics as JSON", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit attackmode on|off|status "
        + "— toggle stricter flood limits", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit maintenance on|off|status "
        + "— toggle network maintenance mode", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit config diff              "
        + "— preview changed config keys", NamedTextColor.GRAY));
    source.sendMessage(Component.text("/conduit failover test <server>   "
        + "— show fallback target", NamedTextColor.GRAY));
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

  private static int doctor(CommandContext<CommandSource> ctx, ProxyServer proxy) {
    ctx.getSource().sendMessage(Component.text(
        ConduitDoctor.buildReport(Conduit.get(), proxy), NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int metricsJson(CommandContext<CommandSource> ctx) {
    ctx.getSource().sendMessage(Component.text(
        ConduitMetricsSnapshot.from(Conduit.get().getDiagnostics()).toJson(), NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int attackModeOn(CommandContext<CommandSource> ctx) {
    Conduit.get().enableAttackMode();
    ctx.getSource().sendMessage(Component.text("Conduit attack mode enabled.",
        NamedTextColor.YELLOW));
    return Command.SINGLE_SUCCESS;
  }

  private static int attackModeOff(CommandContext<CommandSource> ctx) {
    Conduit.get().disableAttackMode();
    ctx.getSource().sendMessage(Component.text("Conduit attack mode disabled.",
        NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int attackModeStatus(CommandContext<CommandSource> ctx) {
    ctx.getSource().sendMessage(Component.text(
        "Conduit attack mode: " + (Conduit.get().isAttackModeEnabled() ? "ON" : "OFF"),
        NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int maintenance(CommandContext<CommandSource> ctx, boolean enable) {
    var manager = Conduit.get().getMaintenanceManager();
    boolean changed = manager.setActive(enable);
    if (!changed) {
      ctx.getSource().sendMessage(Component.text(
          "Maintenance mode is already " + (enable ? "ON" : "OFF") + ".",
          NamedTextColor.YELLOW));
      return 0;
    }
    if (enable) {
      ctx.getSource().sendMessage(Component.text(
          "Maintenance mode ENABLED — non-exempt players can no longer join.",
          NamedTextColor.YELLOW));
    } else {
      ctx.getSource().sendMessage(Component.text(
          "Maintenance mode disabled — the network is open again.", NamedTextColor.GREEN));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int maintenanceStatus(CommandContext<CommandSource> ctx) {
    boolean active = Conduit.get().getMaintenanceManager().isActive();
    ctx.getSource().sendMessage(Component.text(
        "Maintenance mode: " + (active ? "ON" : "OFF"),
        active ? NamedTextColor.YELLOW : NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int configDiff(CommandContext<CommandSource> ctx) {
    try {
      ConduitConfig preview = ConduitConfig.loadPreview(Conduit.get().getConfigDir());
      ctx.getSource().sendMessage(Component.text(
          ConduitConfigDiff.between(Conduit.get().getConfig(), preview).toHumanString(),
          NamedTextColor.AQUA));
      return Command.SINGLE_SUCCESS;
    } catch (RuntimeException ex) {
      ctx.getSource().sendMessage(Component.text(
          "Config diff failed: " + ex.getMessage(), NamedTextColor.RED));
      return 0;
    }
  }

  private static int failoverTest(CommandContext<CommandSource> ctx) {
    String server = StringArgumentType.getString(ctx, "server");
    var target = Conduit.get().getFallbackRouter().simulateFallback(server);
    if (target.isPresent()) {
      ctx.getSource().sendMessage(Component.text(
          "If " + server + " fails, Conduit would route to "
              + target.get().getServerInfo().getName() + ".", NamedTextColor.GREEN));
      return Command.SINGLE_SUCCESS;
    }
    ctx.getSource().sendMessage(Component.text(
        "No healthy fallback target is available for " + server + ".", NamedTextColor.YELLOW));
    return 0;
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
