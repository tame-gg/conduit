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

import com.velocitypowered.proxy.conduit.motd.MotdCache;
import com.velocitypowered.proxy.conduit.network.ConnectionThrottler;

/**
 * Runtime limits applied while operators are mitigating a bot flood.
 *
 * <p>The policy only changes live-tunable values. It does not rewrite {@code conduit.toml}, and
 * disabling attack mode restores the values from the active config snapshot.
 */
public record AttackModePolicy(
    int throttleMaxPerSecond,
    int botFilterThreshold,
    int motdCacheTtlMs) {

  /** Applies the stricter attack-mode limits to live subsystems. */
  public void apply(ConnectionThrottler throttler, BotFilter botFilter, MotdCache motdCache) {
    throttler.setMaxPerSecond(throttleMaxPerSecond);
    botFilter.setThreshold(botFilterThreshold);
    motdCache.setTtlMs(motdCacheTtlMs);
  }

  /** Restores live subsystems to the normal config values. */
  public void restore(ConnectionThrottler throttler, BotFilter botFilter, MotdCache motdCache,
      int normalThrottleMaxPerSecond, int normalBotFilterThreshold, int normalMotdCacheTtlMs) {
    throttler.setMaxPerSecond(normalThrottleMaxPerSecond);
    botFilter.setThreshold(normalBotFilterThreshold);
    motdCache.setTtlMs(normalMotdCacheTtlMs);
  }
}
