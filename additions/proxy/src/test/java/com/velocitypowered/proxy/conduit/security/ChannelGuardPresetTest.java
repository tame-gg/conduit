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

package com.velocitypowered.proxy.conduit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChannelGuardPresetTest {

  @Test
  void auditPresetLogsWithoutDroppingTraffic() {
    ChannelGuardPreset preset = ChannelGuardPreset.parse("audit");

    assertEquals(ChannelGuard.Action.LOG, preset.defaultAction());
    assertTrue(preset.blockList().contains("wdl:init"));
  }

  @Test
  void strictPresetIncludesExploitNamespaces() {
    ChannelGuardPreset preset = ChannelGuardPreset.parse("strict");

    assertEquals(ChannelGuard.Action.KICK, preset.defaultAction());
    assertTrue(preset.blockList().contains("xaero:"));
    assertTrue(preset.blockList().contains("schematica:"));
  }
}
