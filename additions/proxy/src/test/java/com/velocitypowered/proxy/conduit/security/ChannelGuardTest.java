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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelGuardTest {

  @Test
  void matchesExactChannelsAndNamespacePrefixesCaseInsensitively() {
    ChannelGuard guard = new ChannelGuard(
        List.of("wdl:init", "xaero:"), ChannelGuard.Action.DROP, null);

    assertTrue(guard.isBlocked("WDL:INIT"));
    assertTrue(guard.isBlocked("xaero:main"));
    assertFalse(guard.isBlocked("minecraft:brand"));
  }
}
