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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conduit.command.ConduitCommand;
import com.velocitypowered.proxy.conduit.command.ModListCommand;
import com.velocitypowered.proxy.conduit.forward.CommandForwarder;
import com.velocitypowered.proxy.conduit.maintenance.MaintenanceManager;
import com.velocitypowered.proxy.conduit.security.ChannelGuard;
import com.velocitypowered.proxy.conduit.update.UpdateNotifier;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConduitPermissionsTest {

  @Test
  void allKnownNodesAreListedExactlyOnce() {
    List<String> all = ConduitPermissions.all();
    assertEquals(all.size(), new HashSet<>(all).size(), "catalogue must not contain duplicates");

    assertTrue(all.contains(ConduitCommand.PERMISSION));
    assertTrue(all.contains(ModListCommand.PERMISSION));
    assertTrue(all.contains(MaintenanceManager.BYPASS_PERMISSION));
    assertTrue(all.contains(ChannelGuard.BYPASS_PERMISSION));
    assertTrue(all.contains(UpdateNotifier.PERMISSION));
    assertTrue(all.contains(CommandForwarder.EXECUTE_PERMISSION));
  }
}
