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

package com.velocitypowered.proxy.conduit.maintenance;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Network-wide maintenance mode — a feature operators routinely add to Velocity via third-party
 * plugins (Maintenance, etc.). Conduit ships it natively.
 *
 * <p>When maintenance is active, every login is rejected with a configurable message unless the
 * connecting player either holds the {@value #BYPASS_PERMISSION} permission or appears in the
 * configured allow-list. The server-list ping is optionally rewritten so the network visibly
 * advertises that it is down for maintenance.
 *
 * <h3>Why two bypass mechanisms?</h3>
 * Permission checks require a permissions backend (LuckPerms, etc.). The username allow-list lets
 * an owner get in even before any permissions plugin has loaded, which is exactly the moment
 * maintenance mode tends to be toggled. Either one grants access.
 *
 * <h3>Persistence</h3>
 * The runtime toggle is mirrored to {@code <configDir>/maintenance.flag} so that maintenance state
 * survives a proxy restart — otherwise a crash-restart during maintenance would silently reopen
 * the network. The flag file is created on enable and deleted on disable.
 *
 * <p>The singleton {@link #DISABLED} instance registers no listeners and never denies a login.
 */
public class MaintenanceManager {

  /** Permission that exempts a player from maintenance-mode login denial. */
  public static final String BYPASS_PERMISSION = "conduit.maintenance.bypass";

  private static final Logger logger = LogManager.getLogger(MaintenanceManager.class);
  private static final String FLAG_FILE = "maintenance.flag";

  /** Sentinel used when the maintenance subsystem is disabled outright in config. */
  public static final MaintenanceManager DISABLED = new MaintenanceManager(
      null, false, "", "", List.of()) {
    @Override
    public void register(Object plugin, ProxyServer proxy) {
      // no-op
    }

    @Override
    public boolean setActive(boolean active) {
      return false;
    }

    @Override
    public boolean isActive() {
      return false;
    }
  };

  private final Path configDir;
  private final String kickMessage;
  private final String maintenanceMotd;
  private final Set<String> allowlist;
  private volatile boolean active;

  /**
   * Constructs a maintenance manager.
   *
   * @param configDir       directory holding the persisted maintenance flag; may be {@code null}
   *                        only for the {@link #DISABLED} sentinel
   * @param initialActive   the configured start state from {@code conduit.toml}
   * @param kickMessage     MiniMessage string shown to denied players
   * @param maintenanceMotd MiniMessage string used for the server-list MOTD while active; blank to
   *                        leave the MOTD untouched
   * @param allowlist       usernames (case-insensitive) always permitted to connect
   */
  public MaintenanceManager(Path configDir, boolean initialActive, String kickMessage,
      String maintenanceMotd, List<String> allowlist) {
    this.configDir = configDir;
    this.kickMessage = kickMessage;
    this.maintenanceMotd = maintenanceMotd;
    this.allowlist = new CopyOnWriteArraySet<>();
    for (String name : allowlist) {
      if (name != null && !name.isBlank()) {
        this.allowlist.add(name.toLowerCase(Locale.ROOT));
      }
    }
    this.active = initialActive || flagFileExists();
  }

  /** Registers the login and ping listeners on the proxy. */
  public void register(Object plugin, ProxyServer proxy) {
    proxy.getEventManager().register(plugin, this);
    logger.info("[Conduit] MaintenanceManager registered (active={}, {} allow-listed).",
        active, allowlist.size());
  }

  /**
   * Denies non-exempt logins while maintenance is active.
   *
   * <p>Runs at {@link PostOrder#FIRST} so the player is rejected before other plugins do connection
   * setup work that would just be thrown away.
   */
  @Subscribe(order = PostOrder.FIRST)
  public void onLogin(LoginEvent event) {
    if (!active) {
      return;
    }
    Player player = event.getPlayer();
    if (isExempt(player)) {
      return;
    }
    event.setResult(ComponentResult.denied(renderKickMessage()));
  }

  /** Rewrites the server-list MOTD while maintenance is active, if one is configured. */
  @Subscribe(order = PostOrder.NORMAL)
  public void onProxyPing(ProxyPingEvent event) {
    if (!active || maintenanceMotd.isBlank()) {
      return;
    }
    ServerPing ping = event.getPing();
    event.setPing(ping.asBuilder()
        .description(MiniMessage.miniMessage().deserialize(maintenanceMotd))
        .build());
  }

  /** Returns {@code true} if the given player is allowed in despite maintenance mode. */
  public boolean isExempt(Player player) {
    return player.hasPermission(BYPASS_PERMISSION)
        || isAllowlisted(player.getUsername());
  }

  /** Returns {@code true} if the given username is on the maintenance allow-list. */
  public boolean isAllowlisted(String username) {
    return username != null && allowlist.contains(username.toLowerCase(Locale.ROOT));
  }

  /**
   * Pure decision used by both {@link #onLogin} and tests: a login is denied only when maintenance
   * is active and the player is neither permission-exempt nor allow-listed.
   */
  public static boolean shouldDeny(boolean active, boolean hasBypassPermission,
      boolean allowlisted) {
    return active && !hasBypassPermission && !allowlisted;
  }

  /** Returns whether maintenance mode is currently active. */
  public boolean isActive() {
    return active;
  }

  /**
   * Sets the maintenance state and persists it to the flag file.
   *
   * @param active the desired state
   * @return {@code true} if the state changed
   */
  public boolean setActive(boolean active) {
    if (this.active == active) {
      return false;
    }
    this.active = active;
    persistFlag(active);
    logger.warn("[Conduit] Maintenance mode {}.", active ? "ENABLED" : "disabled");
    return true;
  }

  /** Returns an immutable snapshot of allow-listed usernames (lower-cased). */
  public Set<String> getAllowlist() {
    return Set.copyOf(allowlist);
  }

  private Component renderKickMessage() {
    return MiniMessage.miniMessage().deserialize(
        kickMessage.isBlank() ? "<red>The network is currently down for maintenance." : kickMessage);
  }

  private boolean flagFileExists() {
    return configDir != null && Files.exists(configDir.resolve(FLAG_FILE));
  }

  private void persistFlag(boolean active) {
    if (configDir == null) {
      return;
    }
    Path flag = configDir.resolve(FLAG_FILE);
    try {
      if (active) {
        Files.writeString(flag, "Maintenance mode is active. Delete this file to clear it.\n");
      } else {
        Files.deleteIfExists(flag);
      }
    } catch (IOException e) {
      logger.warn("[Conduit] Could not persist maintenance flag: {}", e.getMessage());
    }
  }
}
