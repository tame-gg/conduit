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

package com.velocitypowered.proxy.conduit.luckperms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conduit.maintenance.MaintenanceManager;
import com.velocitypowered.proxy.conduit.permission.ConduitPermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LuckPermsPermissionSeederTest {

  @Test
  void catalogueIncludesMaintenanceBypass() {
    assertTrue(ConduitPermissions.all().contains(MaintenanceManager.BYPASS_PERMISSION));
  }

  @Test
  void seedsViaPermissionRegistryInsert() {
    FakeLuckPermsPlugin plugin = new FakeLuckPermsPlugin();
    List<String> nodes = List.of(
        MaintenanceManager.BYPASS_PERMISSION,
        "conduit.channelguard.bypass");

    int seeded = LuckPermsPermissionSeeder.seedViaRegistry(plugin, nodes);

    assertEquals(2, seeded);
    assertEquals(nodes, plugin.registry.inserted);
  }

  @Test
  void seedsViaConsolePermissionChecks() {
    List<String> checked = new ArrayList<>();
    List<String> nodes = List.of(MaintenanceManager.BYPASS_PERMISSION, "conduit.admin");

    int seeded = LuckPermsPermissionSeeder.seedViaConsoleChecks(permission -> {
      checked.add(permission);
      return false;
    }, nodes);

    assertEquals(2, seeded);
    assertEquals(nodes, checked);
  }

  @Test
  void registrySeedSkipsBlankNodes() {
    FakeLuckPermsPlugin plugin = new FakeLuckPermsPlugin();

    int seeded = LuckPermsPermissionSeeder.seedViaRegistry(plugin,
        java.util.Arrays.asList("conduit.admin", "  ", "", null));

    assertEquals(1, seeded);
    assertEquals(List.of("conduit.admin"), plugin.registry.inserted);
  }

  @Test
  void registrySeedReturnsZeroWhenApiMissing() {
    assertEquals(0, LuckPermsPermissionSeeder.seedViaRegistry(new Object(),
        List.of(MaintenanceManager.BYPASS_PERMISSION)));
  }

  @Test
  void prefersInsertOverOfferWhenBothExist() {
    FakeRegistryWithOffer registry = new FakeRegistryWithOffer();
    FakePluginWithRegistry plugin = new FakePluginWithRegistry(registry);

    int seeded = LuckPermsPermissionSeeder.seedViaRegistry(plugin,
        List.of(MaintenanceManager.BYPASS_PERMISSION));

    assertEquals(1, seeded);
    assertEquals(List.of(MaintenanceManager.BYPASS_PERMISSION), registry.inserted);
    assertTrue(registry.offered.isEmpty(), "insert must be preferred over async offer");
  }

  /** Minimal stand-in for LP's plugin + PermissionRegistry.insert path. */
  static final class FakeLuckPermsPlugin {
    final FakePermissionRegistry registry = new FakePermissionRegistry();

    public FakePermissionRegistry getPermissionRegistry() {
      return registry;
    }
  }

  static final class FakePermissionRegistry {
    final List<String> inserted = new ArrayList<>();

    public void insert(String permission) {
      inserted.add(permission);
    }
  }

  static final class FakePluginWithRegistry {
    private final FakeRegistryWithOffer registry;

    FakePluginWithRegistry(FakeRegistryWithOffer registry) {
      this.registry = registry;
    }

    public FakeRegistryWithOffer getPermissionRegistry() {
      return registry;
    }
  }

  static final class FakeRegistryWithOffer {
    final List<String> inserted = new ArrayList<>();
    final List<String> offered = new ArrayList<>();

    public void insert(String permission) {
      inserted.add(permission);
    }

    public void offer(String permission) {
      offered.add(permission);
    }
  }
}
