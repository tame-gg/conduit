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

import java.util.List;
import java.util.Locale;

/** Named ChannelGuard policies that cover the common operator postures. */
public enum ChannelGuardPreset {
  /** Use the explicitly configured blocklist and action. */
  CUSTOM(ChannelGuard.Action.DROP, List.of()),

  /** Observe suspicious channels without dropping traffic. */
  AUDIT(ChannelGuard.Action.LOG, defaultBlockList()),

  /** Block known cheat channels but avoid broad modded namespaces. */
  MODDED_SAFE(ChannelGuard.Action.DROP, defaultBlockList()),

  /** Kick on known cheat channels and broader minimap/schematic namespaces. */
  STRICT(ChannelGuard.Action.KICK, strictBlockList());

  private final ChannelGuard.Action defaultAction;
  private final List<String> blockList;

  ChannelGuardPreset(ChannelGuard.Action defaultAction, List<String> blockList) {
    this.defaultAction = defaultAction;
    this.blockList = blockList;
  }

  /** Parses a case-insensitive preset name. */
  public static ChannelGuardPreset parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return CUSTOM;
    }
    String normalised = raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    return ChannelGuardPreset.valueOf(normalised);
  }

  /** Returns the action operators should use when no explicit action is set. */
  public ChannelGuard.Action defaultAction() {
    return defaultAction;
  }

  /** Returns the preset blocklist. */
  public List<String> blockList() {
    return blockList;
  }

  private static List<String> defaultBlockList() {
    return List.of(
        "wdl:init",
        "wdl:control",
        "wdl:request",
        "world_downloader:init",
        "world_downloader:control",
        "world_downloader:request");
  }

  private static List<String> strictBlockList() {
    return List.of(
        "wdl:init",
        "wdl:control",
        "wdl:request",
        "world_downloader:init",
        "world_downloader:control",
        "world_downloader:request",
        "xaero:",
        "schematica:",
        "bsm:",
        "5zig:");
  }
}
