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
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.conduit.Conduit;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker.ClientModType;
import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker.PlayerModState;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@code /modlist} command. Shows staff which mod loader and channel set is advertised by
 * each connected player.
 *
 * <h3>Usage</h3>
 * <ul>
 *   <li>{@code /modlist} — prints a one-line summary for every connected player.</li>
 *   <li>{@code /modlist <player>} — prints the detailed channel and known-pack list for the
 *       named player.</li>
 * </ul>
 *
 * <p>Permission required: {@value #PERMISSION}.
 */
public final class ModListCommand {

  /** Permission required to run {@code /modlist}. */
  public static final String PERMISSION = "conduit.modlist";

  private static final Logger logger = LogManager.getLogger(ModListCommand.class);
  /** Cap on per-player channel rows shown in detail view, to keep chat readable. */
  private static final int MAX_CHANNELS_SHOWN = 32;

  private ModListCommand() {}

  /** Registers {@code /modlist} on the proxy command manager. */
  public static void register(ProxyServer proxy, Object plugin) {
    BrigadierCommand command = new BrigadierCommand(buildNode(proxy));
    proxy.getCommandManager().register(
        proxy.getCommandManager().metaBuilder("modlist")
            .plugin(plugin)
            .build(),
        command);
    logger.info("[Conduit] Registered /modlist command.");
  }

  private static LiteralCommandNode<CommandSource> buildNode(ProxyServer proxy) {
    return BrigadierCommand.literalArgumentBuilder("modlist")
        .requires(source -> source.hasPermission(PERMISSION))
        .executes(ctx -> listAll(ctx, proxy))
        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.string())
            .suggests((ctx, builder) -> suggestPlayers(proxy, builder))
            .executes(ctx -> showPlayer(ctx, proxy)))
        .build();
  }

  private static CompletableFuture<Suggestions> suggestPlayers(ProxyServer proxy,
      SuggestionsBuilder builder) {
    String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
    for (Player p : proxy.getAllPlayers()) {
      String name = p.getUsername();
      if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        builder.suggest(name);
      }
    }
    return builder.buildFuture();
  }

  private static int listAll(CommandContext<CommandSource> ctx, ProxyServer proxy) {
    CommandSource source = ctx.getSource();
    ModdedClientTracker tracker = Conduit.get().getClientTracker();
    if (proxy.getPlayerCount() == 0) {
      source.sendMessage(Component.text("No players connected.", NamedTextColor.GRAY));
      return Command.SINGLE_SUCCESS;
    }
    source.sendMessage(Component.text(
        "Mod summary (" + proxy.getPlayerCount() + " players):", NamedTextColor.GOLD));
    int tracked = 0;
    int untracked = 0;
    for (Player player : proxy.getAllPlayers()) {
      PlayerModState state = tracker.getState(player.getUniqueId());
      if (state == null) {
        untracked++;
        source.sendMessage(Component.text(
            "  " + pad(player.getUsername(), 16) + "  (no REGISTER seen yet)",
            NamedTextColor.GRAY));
      } else {
        tracked++;
        source.sendMessage(Component.text(
            "  " + pad(player.getUsername(), 16) + "  "
            + pad(state.modType().name(), 14) + "  "
            + state.registeredChannels().size() + " channels",
            colorFor(state.modType())));
      }
    }
    source.sendMessage(Component.text(
        "Tracked: " + tracked + "   Untracked: " + untracked,
        NamedTextColor.DARK_GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static int showPlayer(CommandContext<CommandSource> ctx, ProxyServer proxy) {
    CommandSource source = ctx.getSource();
    String name = StringArgumentType.getString(ctx, "player");
    Optional<Player> playerOpt = proxy.getPlayer(name);
    if (playerOpt.isEmpty()) {
      source.sendMessage(Component.text(
          "Player not found: " + name, NamedTextColor.RED));
      return 0;
    }
    Player player = playerOpt.get();
    PlayerModState state = Conduit.get().getClientTracker().getState(player.getUniqueId());
    if (state == null) {
      source.sendMessage(Component.text(
          name + ": no REGISTER message has been seen for this player yet.",
          NamedTextColor.YELLOW));
      return Command.SINGLE_SUCCESS;
    }
    source.sendMessage(Component.text(
        name + " — " + state.modType().name(), colorFor(state.modType())));
    source.sendMessage(Component.text(
        "  Detected " + msSince(state.detectedAtMs()) + " ms ago", NamedTextColor.DARK_GRAY));
    List<String> channels = state.registeredChannels();
    source.sendMessage(Component.text(
        "  Channels (" + channels.size() + "):", NamedTextColor.GRAY));
    int shown = Math.min(channels.size(), MAX_CHANNELS_SHOWN);
    for (int i = 0; i < shown; i++) {
      source.sendMessage(Component.text("    " + channels.get(i), NamedTextColor.AQUA));
    }
    if (channels.size() > MAX_CHANNELS_SHOWN) {
      source.sendMessage(Component.text(
          "    … " + (channels.size() - MAX_CHANNELS_SHOWN) + " more",
          NamedTextColor.DARK_GRAY));
    }
    if (!state.knownPackNamespaces().isEmpty()) {
      source.sendMessage(Component.text(
          "  Known-pack namespaces (" + state.knownPackNamespaces().size() + "):",
          NamedTextColor.GRAY));
      for (String ns : state.knownPackNamespaces()) {
        source.sendMessage(Component.text("    " + ns, NamedTextColor.AQUA));
      }
    }
    return Command.SINGLE_SUCCESS;
  }

  private static long msSince(long whenMs) {
    return Math.max(0L, System.currentTimeMillis() - whenMs);
  }

  private static String pad(String s, int width) {
    if (s.length() >= width) {
      return s;
    }
    StringBuilder sb = new StringBuilder(width);
    sb.append(s);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  private static NamedTextColor colorFor(ClientModType type) {
    return switch (type) {
      case VANILLA -> NamedTextColor.GREEN;
      case FABRIC -> NamedTextColor.LIGHT_PURPLE;
      case LEGACY_FORGE -> NamedTextColor.GOLD;
      case NEOFORGE -> NamedTextColor.AQUA;
      case UNKNOWN_MODDED -> NamedTextColor.YELLOW;
    };
  }
}
