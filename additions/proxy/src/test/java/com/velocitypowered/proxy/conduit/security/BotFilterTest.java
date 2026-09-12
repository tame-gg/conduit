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

    BotFilter.Attempt attempt = filter.beginHandshake(address);
    filter.completeHandshake(attempt);
    Thread.sleep(2);
    filter.timeoutHandshake(attempt);

    assertFalse(filter.isBlocked(address));
  }

  @Test
  void timedOutHandshakesTriggerBlockAndCanBeUnblocked() throws Exception {
    BotFilter filter = new BotFilter(1, 2);
    InetAddress address = InetAddress.getByName("127.0.0.2");

    BotFilter.Attempt first = filter.beginHandshake(address);
    Thread.sleep(2);
    filter.timeoutHandshake(first);
    assertFalse(filter.isBlocked(address));

    BotFilter.Attempt second = filter.beginHandshake(address);
    Thread.sleep(2);
    filter.timeoutHandshake(second);
    assertTrue(filter.isBlocked(address));
    assertTrue(filter.unblock(address));
    assertFalse(filter.isBlocked(address));
  }

  /**
   * The flood case the per-address design got wrong: many connections opened back to back, each
   * timing out later. With one shared pending slot per address, every timeout measured the newest
   * connection's age and counted nothing.
   */
  @Test
  void concurrentHandshakesFromOneAddressAreCountedIndependently() throws Exception {
    BotFilter filter = new BotFilter(1, 3);
    InetAddress address = InetAddress.getByName("127.0.0.3");

    BotFilter.Attempt[] attempts = new BotFilter.Attempt[3];
    for (int i = 0; i < attempts.length; i++) {
      attempts[i] = filter.beginHandshake(address);
    }
    Thread.sleep(2);
    for (BotFilter.Attempt attempt : attempts) {
      filter.timeoutHandshake(attempt);
    }

    assertTrue(filter.isBlocked(address));
  }

  /** One player completing a handshake must not clear another connection's pending state. */
  @Test
  void oneCompletedHandshakeDoesNotExcuseTheOthers() throws Exception {
    BotFilter filter = new BotFilter(1, 2);
    InetAddress address = InetAddress.getByName("127.0.0.4");

    BotFilter.Attempt legitimate = filter.beginHandshake(address);
    BotFilter.Attempt bot1 = filter.beginHandshake(address);
    BotFilter.Attempt bot2 = filter.beginHandshake(address);
    filter.completeHandshake(legitimate);
    Thread.sleep(2);
    filter.timeoutHandshake(legitimate);
    filter.timeoutHandshake(bot1);
    filter.timeoutHandshake(bot2);

    assertTrue(filter.isBlocked(address));
  }

  /** An attempt already counted must not be counted again if the timer somehow fires twice. */
  @Test
  void anAttemptIsOnlyCountedOnce() throws Exception {
    BotFilter filter = new BotFilter(1, 2);
    InetAddress address = InetAddress.getByName("127.0.0.5");

    BotFilter.Attempt attempt = filter.beginHandshake(address);
    Thread.sleep(2);
    filter.timeoutHandshake(attempt);
    filter.timeoutHandshake(attempt);

    assertFalse(filter.isBlocked(address));
  }

  /** IPv6 sources are grouped by /64, so rotating the host bits cannot reset the counter. */
  @Test
  void ipv6SourcesAreGroupedByPrefix() throws Exception {
    BotFilter filter = new BotFilter(1, 2);
    InetAddress first = InetAddress.getByName("2001:db8::1");
    InetAddress second = InetAddress.getByName("2001:db8::dead:beef");

    BotFilter.Attempt a = filter.beginHandshake(first);
    BotFilter.Attempt b = filter.beginHandshake(second);
    Thread.sleep(2);
    filter.timeoutHandshake(a);
    filter.timeoutHandshake(b);

    assertTrue(filter.isBlocked(InetAddress.getByName("2001:db8::5")));
    assertFalse(filter.isBlocked(InetAddress.getByName("2001:db9::1")));
  }

  /** A disabled filter tracks nothing, and enabling it again starts from a clean slate. */
  @Test
  void disabledFilterNeitherTracksNorBlocks() throws Exception {
    BotFilter filter = new BotFilter(1, 1, false);
    InetAddress address = InetAddress.getByName("127.0.0.6");

    assertFalse(filter.isEnabled());
    BotFilter.Attempt attempt = filter.beginHandshake(address);
    Thread.sleep(2);
    filter.timeoutHandshake(attempt);
    assertFalse(filter.isBlocked(address));

    filter.setEnabled(true);
    BotFilter.Attempt tracked = filter.beginHandshake(address);
    Thread.sleep(2);
    filter.timeoutHandshake(tracked);
    assertTrue(filter.isBlocked(address));

    filter.setEnabled(false);
    assertFalse(filter.isBlocked(address));
  }
}
