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

import java.net.InetAddress;
import java.util.Arrays;

/**
 * A network-prefix grouping key for per-source rate limiting.
 *
 * <p>Keying flood mitigation on a full address is safe for IPv4, where an attacker pays for every
 * distinct source, but useless for IPv6: a single allocated {@code /64} contains 2<sup>64</sup>
 * addresses, so an attacker rotates the low bits and every connection looks like a brand-new
 * source — defeating per-source counters and thrashing their bounded LRU maps at the same time.
 * Masking the address down to the prefix that is actually allocated as a unit (a {@code /64} for
 * IPv6, the address itself for IPv4 by default) makes the key track the entity paying for the
 * addresses rather than the address.
 *
 * <p>Instances are immutable and safe to use as hash-map keys.
 */
public final class SubnetKey {

  private final byte[] bytes;
  private final int prefixBits;
  private final int hash;

  private SubnetKey(byte[] bytes, int prefixBits) {
    this.bytes = bytes;
    this.prefixBits = prefixBits;
    this.hash = Arrays.hashCode(bytes);
  }

  /**
   * Returns the key covering {@code address}, masked to the prefix length configured for its
   * family.
   *
   * @param address    the remote address
   * @param ipv4Prefix prefix length applied to IPv4 addresses (1–32)
   * @param ipv6Prefix prefix length applied to IPv6 addresses (1–128)
   */
  public static SubnetKey of(InetAddress address, int ipv4Prefix, int ipv6Prefix) {
    byte[] raw = address.getAddress();
    int max = raw.length * 8;
    int prefix = raw.length == 4 ? ipv4Prefix : ipv6Prefix;
    if (prefix < 1) {
      prefix = 1;
    }
    if (prefix > max) {
      prefix = max;
    }
    byte[] masked = new byte[raw.length];
    int fullBytes = prefix / 8;
    System.arraycopy(raw, 0, masked, 0, fullBytes);
    int remainingBits = prefix % 8;
    if (remainingBits != 0 && fullBytes < raw.length) {
      masked[fullBytes] = (byte) (raw[fullBytes] & (0xFF << (8 - remainingBits)));
    }
    return new SubnetKey(masked, prefix);
  }

  /** Returns the prefix length this key was masked to. */
  public int prefixBits() {
    return prefixBits;
  }

  /** Returns {@code true} when this key covers exactly one address. */
  public boolean isSingleAddress() {
    return prefixBits == bytes.length * 8;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof SubnetKey key
        && prefixBits == key.prefixBits
        && Arrays.equals(bytes, key.bytes);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  /**
   * Renders the key in CIDR notation ({@code 203.0.113.0/24}), or as a bare address when the key
   * covers a single host.
   */
  @Override
  public String toString() {
    StringBuilder out = new StringBuilder();
    if (bytes.length == 4) {
      for (int i = 0; i < 4; i++) {
        if (i > 0) {
          out.append('.');
        }
        out.append(bytes[i] & 0xFF);
      }
    } else {
      for (int i = 0; i < bytes.length; i += 2) {
        if (i > 0) {
          out.append(':');
        }
        out.append(Integer.toHexString(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF)));
      }
    }
    if (!isSingleAddress()) {
      out.append('/').append(prefixBits);
    }
    return out.toString();
  }
}
