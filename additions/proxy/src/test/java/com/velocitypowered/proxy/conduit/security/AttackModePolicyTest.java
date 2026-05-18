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

import com.velocitypowered.proxy.conduit.network.ConnectionThrottler;
import com.velocitypowered.proxy.conduit.motd.MotdCache;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class AttackModePolicyTest {

  @Test
  void appliesAndRestoresRuntimeLimits() throws Exception {
    ConnectionThrottler throttler = new ConnectionThrottler(30);
    BotFilter botFilter = new BotFilter(3000, 10);
    MotdCache motdCache = new MotdCache(2000);
    AttackModePolicy policy = new AttackModePolicy(8, 3, 10000);

    policy.apply(throttler, botFilter, motdCache);

    assertEquals(8, throttler.getMaxPerSecond());
    assertEquals(3, botFilter.getThreshold());
    assertEquals(10000, motdCache.getTtlMs());

    policy.restore(throttler, botFilter, motdCache, 30, 10, 2000);

    assertEquals(30, throttler.getMaxPerSecond());
    assertEquals(10, botFilter.getThreshold());
    assertEquals(2000, motdCache.getTtlMs());
  }

  @Test
  void stricterThrottleActuallyDropsSooner() throws Exception {
    ConnectionThrottler throttler = new ConnectionThrottler(2);
    InetAddress address = InetAddress.getByName("203.0.113.10");

    throttler.isThrottled(address);
    throttler.isThrottled(address);

    assertEquals(true, throttler.isThrottled(address));
  }
}
