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

package com.velocitypowered.proxy.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConduitConfigTest {

  @TempDir
  Path tempDir;

  @Test
  void loadsConfiguredKnownPackLimit() throws Exception {
    Files.writeString(tempDir.resolve("conduit.toml"),
        """
        [modded]
        max-known-packs = 2048
        """);

    ConduitConfig config = ConduitConfig.load(tempDir);

    assertEquals(2048, config.getMaxKnownPacks());
  }

  @Test
  void rejectsInvalidChannelGuardAction() throws Exception {
    Files.writeString(tempDir.resolve("conduit.toml"),
        """
        [security]
        channel-guard-action = "ban"
        """);

    assertThrows(IllegalArgumentException.class, () -> ConduitConfig.load(tempDir));
  }
}
