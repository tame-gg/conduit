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

package com.velocitypowered.proxy.conduit.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.conduit.ConduitConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConduitConfigDiffTest {

  @TempDir
  Path tempDir;

  @Test
  void reportsLiveAndRestartRequiredChanges() throws Exception {
    Files.writeString(tempDir.resolve("conduit.toml"),
        """
        [network]
        connection-throttle-max-per-second = 30
        write-buffer-high-watermark = 2097152
        """);
    ConduitConfig before = ConduitConfig.load(tempDir);

    Files.writeString(tempDir.resolve("conduit.toml"),
        """
        [network]
        connection-throttle-max-per-second = 12
        write-buffer-high-watermark = 4194304
        """);
    ConduitConfig after = ConduitConfig.loadPreview(tempDir);

    ConduitConfigDiff diff = ConduitConfigDiff.between(before, after);

    assertTrue(diff.toHumanString().contains("connection-throttle-max-per-second: 30 -> 12 (live)"));
    assertTrue(diff.toHumanString().contains("write-buffer-high-watermark: 2097152 -> 4194304 (restart)"));
  }
}
