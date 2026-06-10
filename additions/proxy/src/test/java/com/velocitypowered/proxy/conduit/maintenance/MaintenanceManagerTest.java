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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link MaintenanceManager}. */
class MaintenanceManagerTest {

  @Test
  void shouldDenyOnlyActiveAndNotExempt() {
    // inactive → never deny
    assertFalse(MaintenanceManager.shouldDeny(false, false, false));
    assertFalse(MaintenanceManager.shouldDeny(false, true, false));
    // active and not exempt → deny
    assertTrue(MaintenanceManager.shouldDeny(true, false, false));
    // active but permission-exempt → allow
    assertFalse(MaintenanceManager.shouldDeny(true, true, false));
    // active but allow-listed → allow
    assertFalse(MaintenanceManager.shouldDeny(true, false, true));
  }

  @Test
  void allowlistIsCaseInsensitive(@TempDir Path dir) {
    MaintenanceManager m = new MaintenanceManager(
        dir, false, "down", "", List.of("Notch", "jeb_"));
    assertTrue(m.isAllowlisted("notch"));
    assertTrue(m.isAllowlisted("NOTCH"));
    assertTrue(m.isAllowlisted("jeb_"));
    assertFalse(m.isAllowlisted("herobrine"));
    assertFalse(m.isAllowlisted(null));
  }

  @Test
  void togglePersistsFlagFile(@TempDir Path dir) {
    MaintenanceManager m = new MaintenanceManager(dir, false, "down", "", List.of());
    assertFalse(m.isActive());
    Path flag = dir.resolve("maintenance.flag");

    assertTrue(m.setActive(true), "enabling should report a change");
    assertTrue(m.isActive());
    assertTrue(Files.exists(flag), "flag file should be written on enable");

    // no-op toggle returns false
    assertFalse(m.setActive(true));

    assertTrue(m.setActive(false));
    assertFalse(m.isActive());
    assertFalse(Files.exists(flag), "flag file should be removed on disable");
  }

  @Test
  void stateIsRestoredFromFlagFileOnStartup(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("maintenance.flag"), "active");
    MaintenanceManager m = new MaintenanceManager(dir, false, "down", "", List.of());
    assertTrue(m.isActive(), "an existing flag file should re-activate maintenance on startup");
  }

  @Test
  void disabledSentinelNeverActivates(@TempDir Path dir) {
    assertFalse(MaintenanceManager.DISABLED.isActive());
    assertFalse(MaintenanceManager.DISABLED.setActive(true));
    assertFalse(MaintenanceManager.DISABLED.isActive());
  }
}
