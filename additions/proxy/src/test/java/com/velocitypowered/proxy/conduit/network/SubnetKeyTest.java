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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class SubnetKeyTest {

  private static SubnetKey key(String address, int v4, int v6) throws Exception {
    return SubnetKey.of(InetAddress.getByName(address), v4, v6);
  }

  @Test
  void ipv4DefaultsToOneKeyPerAddress() throws Exception {
    assertEquals(key("203.0.113.1", 32, 64), key("203.0.113.1", 32, 64));
    assertNotEquals(key("203.0.113.1", 32, 64), key("203.0.113.2", 32, 64));
    assertTrue(key("203.0.113.1", 32, 64).isSingleAddress());
  }

  @Test
  void ipv6AddressesInOneSlashSixtyFourShareTheSameKey() throws Exception {
    assertEquals(key("2001:db8::1", 32, 64), key("2001:db8::dead:beef", 32, 64));
    assertNotEquals(key("2001:db8::1", 32, 64), key("2001:db9::1", 32, 64));
  }

  @Test
  void masksPartialBytes() throws Exception {
    // /28 keeps the high nibble of the fourth byte: 203.0.113.17 -> 203.0.113.16/28
    assertEquals(key("203.0.113.17", 28, 64), key("203.0.113.31", 28, 64));
    assertNotEquals(key("203.0.113.17", 28, 64), key("203.0.113.32", 28, 64));
    assertEquals("203.0.113.16/28", key("203.0.113.17", 28, 64).toString());
  }

  @Test
  void clampsPrefixesToTheAddressFamily() throws Exception {
    assertEquals(32, key("203.0.113.1", 99, 64).prefixBits());
    assertEquals(1, key("203.0.113.1", 0, 64).prefixBits());
    assertEquals(128, key("2001:db8::1", 32, 999).prefixBits());
  }

  @Test
  void rendersCidrNotation() throws Exception {
    assertEquals("203.0.113.0/24", key("203.0.113.9", 24, 64).toString());
    assertEquals("203.0.113.9", key("203.0.113.9", 32, 64).toString());
    assertEquals("2001:db8:0:0:0:0:0:0/64", key("2001:db8::1", 32, 64).toString());
  }
}
