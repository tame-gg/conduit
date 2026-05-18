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

package com.velocitypowered.proxy.conduit.routing;

import com.velocitypowered.proxy.conduit.modded.ModdedClientTracker.ClientModType;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Per-backend allow rules for detected client mod loaders. */
public final class ModCompatibilityRules {

  /** No rules: preserve Velocity's default behavior. */
  public static final ModCompatibilityRules ALLOW_ALL = new ModCompatibilityRules(Map.of());

  private final Map<String, Set<ClientModType>> allowedByServer;

  private ModCompatibilityRules(Map<String, Set<ClientModType>> allowedByServer) {
    this.allowedByServer = allowedByServer;
  }

  /**
   * Parses rules in {@code server=LOADER,LOADER} form.
   *
   * <p>Example: {@code lobby=VANILLA,FABRIC}. Server names are case-insensitive on lookup.
   */
  public static ModCompatibilityRules parse(List<String> rawRules) {
    if (rawRules == null || rawRules.isEmpty()) {
      return ALLOW_ALL;
    }
    Map<String, Set<ClientModType>> parsed = new LinkedHashMap<>();
    for (String raw : rawRules) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      int split = raw.indexOf('=');
      if (split <= 0 || split == raw.length() - 1) {
        throw new IllegalArgumentException(
            "mod-compatibility rule must be server=LOADER,LOADER: " + raw);
      }
      String server = raw.substring(0, split).trim().toLowerCase(Locale.ROOT);
      EnumSet<ClientModType> loaders = EnumSet.noneOf(ClientModType.class);
      for (String token : raw.substring(split + 1).split(",")) {
        loaders.add(ClientModType.valueOf(token.trim().toUpperCase(Locale.ROOT)));
      }
      parsed.put(server, Set.copyOf(loaders));
    }
    return parsed.isEmpty() ? ALLOW_ALL : new ModCompatibilityRules(Map.copyOf(parsed));
  }

  /** Returns true when the detected loader may connect to the named server. */
  public boolean isAllowed(String serverName, ClientModType modType) {
    Set<ClientModType> allowed = allowedByServer.get(serverName.toLowerCase(Locale.ROOT));
    return allowed == null || allowed.contains(modType);
  }

  /** Returns whether any rules are active. */
  public boolean isEnabled() {
    return !allowedByServer.isEmpty();
  }

  /** Returns a stable view of the configured server rules. */
  public Map<String, Set<ClientModType>> asMap() {
    return allowedByServer;
  }

  @Override
  public String toString() {
    return allowedByServer.toString();
  }
}
