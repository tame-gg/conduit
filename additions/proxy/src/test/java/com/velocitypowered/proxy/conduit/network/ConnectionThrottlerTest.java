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

package com.velocitypowered.proxy.conduit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class ConnectionThrottlerTest {

  @Test
  void limitsConnectionsPerIpInsideOneSecondWindow() throws Exception {
    ConnectionThrottler throttler = new ConnectionThrottler(2);
    InetAddress address = InetAddress.getByName("127.0.0.3");

    assertFalse(throttler.isThrottled(address));
    assertFalse(throttler.isThrottled(address));
    assertTrue(throttler.isThrottled(address));
    assertEquals(1, throttler.trackedIpCount());

    throttler.reset();
    assertEquals(0, throttler.trackedIpCount());
  }
}
