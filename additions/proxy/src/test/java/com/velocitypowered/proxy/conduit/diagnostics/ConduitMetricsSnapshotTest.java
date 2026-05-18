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

class ConduitMetricsSnapshotTest {

  @TempDir
  Path tempDir;

  @Test
  void rendersDiagnosticsAsStableJson() throws Exception {
    Files.writeString(tempDir.resolve("conduit.toml"), "[diagnostics]\nenabled = true\n");
    ConduitDiagnostics diagnostics = new ConduitDiagnostics(ConduitConfig.load(tempDir));

    diagnostics.recordConnection("notazandi");
    diagnostics.recordHandshakeCacheHit("notazandi");
    diagnostics.recordChannelBlocked("notazandi", "wdl:init", "DROP");

    String json = ConduitMetricsSnapshot.from(diagnostics).toJson();

    assertTrue(json.contains("\"totalConnections\":1"));
    assertTrue(json.contains("\"handshakeCacheHits\":1"));
    assertTrue(json.contains("\"channelsBlocked\":1"));
  }
}
