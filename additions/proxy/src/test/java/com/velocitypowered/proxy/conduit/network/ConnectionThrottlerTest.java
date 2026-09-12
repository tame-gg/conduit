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

  @Test
  void groupsIpv6SourcesByPrefixSoHostBitsCannotEscapeTheLimit() throws Exception {
    ConnectionThrottler throttler = new ConnectionThrottler(2);

    assertFalse(throttler.isThrottled(InetAddress.getByName("2001:db8::1")));
    assertFalse(throttler.isThrottled(InetAddress.getByName("2001:db8::2")));
    assertTrue(throttler.isThrottled(InetAddress.getByName("2001:db8::ffff")));
    // A different /64 is a different source.
    assertFalse(throttler.isThrottled(InetAddress.getByName("2001:db9::1")));
    assertEquals(2, throttler.trackedIpCount());
  }

  @Test
  void ipv4PrefixGroupsTheWholeNetwork() throws Exception {
    ConnectionThrottler throttler = new ConnectionThrottler(2);
    throttler.setPrefixes(24, 64);

    assertFalse(throttler.isThrottled(InetAddress.getByName("203.0.113.7")));
    assertFalse(throttler.isThrottled(InetAddress.getByName("203.0.113.8")));
    assertTrue(throttler.isThrottled(InetAddress.getByName("203.0.113.9")));
    assertEquals(1, throttler.trackedIpCount());
  }

  @Test
  void changingPrefixesClearsStaleKeys() throws Exception {
    ConnectionThrottler throttler = new ConnectionThrottler(2);
    assertFalse(throttler.isThrottled(InetAddress.getByName("203.0.113.7")));
    assertEquals(1, throttler.trackedIpCount());

    throttler.setPrefixes(24, 64);
    assertEquals(0, throttler.trackedIpCount());
  }

  @Test
  void disabledThrottlerAdmitsEverythingUntilSwitchedOn() throws Exception {
    ConnectionThrottler throttler = new ConnectionThrottler(1, false);
    InetAddress address = InetAddress.getByName("127.0.0.4");

    assertFalse(throttler.isThrottled(address));
    assertFalse(throttler.isThrottled(address));
    assertEquals(0, throttler.trackedIpCount());

    throttler.setEnabled(true);
    assertFalse(throttler.isThrottled(address));
    assertTrue(throttler.isThrottled(address));

    throttler.setEnabled(false);
    assertFalse(throttler.isThrottled(address));
    assertEquals(0, throttler.trackedIpCount());
  }
}
