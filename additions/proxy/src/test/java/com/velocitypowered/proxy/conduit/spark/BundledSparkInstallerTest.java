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

package com.velocitypowered.proxy.conduit.spark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledSparkInstallerTest {

  @TempDir
  Path tempDir;

  @Test
  void extractsBundledSparkWhenNoSparkPluginExists() throws Exception {
    byte[] jarBytes = new byte[] {1, 2, 3, 4};

    BundledSparkInstaller.InstallResult result = BundledSparkInstaller.install(
        tempDir, () -> new ByteArrayInputStream(jarBytes));

    assertEquals(BundledSparkInstaller.InstallResult.INSTALLED, result);
    assertArrayEquals(jarBytes, Files.readAllBytes(
        tempDir.resolve("plugins").resolve("spark-velocity-bundled.jar")));
  }

  @Test
  void doesNotOverwriteOperatorManagedSparkPlugin() throws Exception {
    Path pluginsDir = tempDir.resolve("plugins");
    Files.createDirectories(pluginsDir);
    Path existing = pluginsDir.resolve("spark-custom.jar");
    byte[] existingBytes = new byte[] {9, 8, 7};
    Files.write(existing, existingBytes);

    BundledSparkInstaller.InstallResult result = BundledSparkInstaller.install(
        tempDir, () -> new ByteArrayInputStream(new byte[] {1, 2, 3}));

    assertEquals(BundledSparkInstaller.InstallResult.SKIPPED_EXISTING_SPARK, result);
    assertArrayEquals(existingBytes, Files.readAllBytes(existing));
    assertFalse(Files.exists(pluginsDir.resolve("spark-velocity-bundled.jar")));
  }
}
