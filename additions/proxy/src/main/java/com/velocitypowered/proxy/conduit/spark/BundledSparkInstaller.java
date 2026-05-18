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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Installs the bundled spark Velocity plugin jar into the runtime plugins directory.
 *
 * <p>spark remains a normal Velocity plugin at runtime. Conduit only ships the official shaded
 * spark-velocity jar and extracts it before Velocity scans {@code plugins/}.
 */
public final class BundledSparkInstaller {

  static final String BUNDLED_SPARK_RESOURCE =
      "/com/velocitypowered/proxy/conduit/bundled/spark-velocity.jar";
  static final String BUNDLED_SPARK_FILE = "spark-velocity-bundled.jar";

  private BundledSparkInstaller() {}

  /** Result of attempting to install the bundled spark plugin. */
  public enum InstallResult {
    /** The bundled spark jar was written into {@code plugins/}. */
    INSTALLED,
    /** An operator-managed spark jar already exists, so Conduit left it alone. */
    SKIPPED_EXISTING_SPARK,
    /** The bundled spark jar was not present in the Conduit jar. */
    SKIPPED_MISSING_RESOURCE
  }

  /**
   * Installs the bundled spark plugin into {@code <rootDir>/plugins}.
   *
   * @param rootDir the proxy working directory
   * @return the install result
   * @throws IOException if the bundled jar cannot be written
   */
  public static InstallResult install(Path rootDir) throws IOException {
    return install(rootDir, () -> BundledSparkInstaller.class.getResourceAsStream(
        BUNDLED_SPARK_RESOURCE));
  }

  static InstallResult install(Path rootDir, InputStreamSupplier source) throws IOException {
    Path pluginsDir = rootDir.resolve("plugins");
    Files.createDirectories(pluginsDir);

    if (hasOperatorManagedSparkJar(pluginsDir)) {
      return InstallResult.SKIPPED_EXISTING_SPARK;
    }

    try (InputStream in = source.open()) {
      if (in == null) {
        return InstallResult.SKIPPED_MISSING_RESOURCE;
      }
      Path target = pluginsDir.resolve(BUNDLED_SPARK_FILE);
      Path tmp = Files.createTempFile(pluginsDir, "spark-velocity-bundled", ".jar.tmp");
      try {
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(tmp);
        throw e;
      }
      return InstallResult.INSTALLED;
    }
  }

  private static boolean hasOperatorManagedSparkJar(Path pluginsDir) throws IOException {
    if (!Files.isDirectory(pluginsDir)) {
      return false;
    }
    try (Stream<Path> files = Files.list(pluginsDir)) {
      return files
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .anyMatch(BundledSparkInstaller::isOperatorManagedSparkJar);
    }
  }

  private static boolean isOperatorManagedSparkJar(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    return lower.startsWith("spark")
        && lower.endsWith(".jar")
        && !lower.equals(BUNDLED_SPARK_FILE);
  }

  @FunctionalInterface
  interface InputStreamSupplier {
    InputStream open() throws IOException;
  }
}
