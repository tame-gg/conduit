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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class BotFilterTest {

  @Test
  void completedHandshakeIsNotCountedAsTimeout() throws Exception {
    BotFilter filter = new BotFilter(1, 1);
    InetAddress address = InetAddress.getByName("127.0.0.1");

    filter.recordHandshakeStart(address);
    filter.recordHandshakeComplete(address);
    Thread.sleep(2);
    filter.recordHandshakeTimeout(address);

    assertFalse(filter.isBlocked(address));
  }

  @Test
  void timedOutHandshakesTriggerBlockAndCanBeUnblocked() throws Exception {
    BotFilter filter = new BotFilter(1, 2);
    InetAddress address = InetAddress.getByName("127.0.0.2");

    filter.recordHandshakeStart(address);
    Thread.sleep(2);
    filter.recordHandshakeTimeout(address);
    assertFalse(filter.isBlocked(address));

    filter.recordHandshakeStart(address);
    Thread.sleep(2);
    filter.recordHandshakeTimeout(address);
    assertTrue(filter.isBlocked(address));
    assertTrue(filter.unblock(address));
    assertFalse(filter.isBlocked(address));
  }
}
