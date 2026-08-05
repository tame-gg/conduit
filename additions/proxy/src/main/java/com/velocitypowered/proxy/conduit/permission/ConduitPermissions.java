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

package com.velocitypowered.proxy.conduit.permission;

import com.velocitypowered.proxy.conduit.command.ConduitCommand;
import com.velocitypowered.proxy.conduit.command.ModListCommand;
import com.velocitypowered.proxy.conduit.forward.CommandForwarder;
import com.velocitypowered.proxy.conduit.maintenance.MaintenanceManager;
import com.velocitypowered.proxy.conduit.security.ChannelGuard;
import com.velocitypowered.proxy.conduit.update.UpdateNotifier;
import java.util.List;

/**
 * Canonical catalogue of Conduit permission nodes.
 *
 * <p>Velocity has no permission registry, so nodes that are only checked at rare moments (for
 * example {@link MaintenanceManager#BYPASS_PERMISSION} during an active maintenance window) never
 * appear in LuckPerms tab-complete or the web editor. {@link
 * com.velocitypowered.proxy.conduit.luckperms.LuckPermsPermissionSeeder} publishes this catalogue
 * into LuckPerms' suggestion tree at startup.
 */
public final class ConduitPermissions {

  private ConduitPermissions() {}

  /**
   * Every first-class Conduit permission string, in stable order.
   *
   * <p>Keep this list in sync when adding a new {@code conduit.*} node that operators are expected
   * to grant via LuckPerms.
   */
  public static List<String> all() {
    return List.of(
        ConduitCommand.PERMISSION,
        ModListCommand.PERMISSION,
        MaintenanceManager.BYPASS_PERMISSION,
        ChannelGuard.BYPASS_PERMISSION,
        UpdateNotifier.PERMISSION,
        CommandForwarder.EXECUTE_PERMISSION);
  }
}
