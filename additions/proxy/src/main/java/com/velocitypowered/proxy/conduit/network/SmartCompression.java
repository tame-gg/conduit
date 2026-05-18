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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.zip.Deflater;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Drop-in replacement for Velocity's compression layer that skips compression when the estimated
 * savings would not exceed a configurable threshold.
 *
 * <p>Vanilla Velocity compresses every packet above the threshold regardless of whether the data
 * is actually compressible (e.g., image data, already-compressed audio, encrypted payloads).
 * SmartCompression adds a pre-flight byte-saving check using a fast heuristic: it samples the
 * first 128 bytes of the payload to estimate the Shannon entropy.  If entropy is high
 * (≥ {@value #HIGH_ENTROPY_CUTOFF} bits per byte) the payload is almost certainly already
 * compressed or encrypted, and we skip deflation.
 *
 * <p>For payloads that pass the entropy check but still compress poorly (compressed size within
 * {@code minDeltaBytes} of raw size) the raw payload is sent instead.
 *
 * <p>Thread-safety: each {@link SmartDeflater} is stateful and must not be shared across threads.
 * Call {@link #createDeflater} once per pipeline.
 */
public final class SmartCompression {

  private static final Logger logger = LogManager.getLogger(SmartCompression.class);

  /** Entropy threshold above which we assume the data is already compressed or encrypted. */
  private static final double HIGH_ENTROPY_CUTOFF = 6.8;

  /** Bytes to sample for the entropy heuristic.  Must be ≤ the actual payload length. */
  private static final int ENTROPY_SAMPLE_SIZE = 128;

  private final int compressionThreshold;
  private volatile int minDeltaBytes;

  /**
   * Constructs a {@code SmartCompression} context.
   *
   * @param compressionThreshold minimum raw packet size (in bytes) before compression is attempted
   * @param minDeltaBytes        minimum byte savings required to actually send the compressed form
   */
  public SmartCompression(int compressionThreshold, int minDeltaBytes) {
    this.compressionThreshold = compressionThreshold;
    this.minDeltaBytes = minDeltaBytes;
  }

  /** Updates the minimum compression savings threshold. */
  public void setMinDeltaBytes(int minDeltaBytes) {
    this.minDeltaBytes = minDeltaBytes;
  }

  /**
   * Creates a thread-local deflater instance pre-configured with this compression context.
   * The caller is responsible for calling {@link SmartDeflater#close()} when the pipeline closes.
   */
  public SmartDeflater createDeflater(int level) {
    return new SmartDeflater(level, compressionThreshold, this);
  }

  /**
   * Returns {@code true} if the packet payload at {@code buf[readerIndex..readerIndex+length]}
   * looks compressible enough to warrant running DEFLATE on it.
   */
  static boolean isLikelyCompressible(ByteBuf buf, int length) {
    if (length < ENTROPY_SAMPLE_SIZE) {
      return true; // too short to estimate, just compress
    }
    int sampleLen = Math.min(length, ENTROPY_SAMPLE_SIZE);
    int readerIndex = buf.readerIndex();

    int[] freq = new int[256];
    for (int i = 0; i < sampleLen; i++) {
      freq[buf.getByte(readerIndex + i) & 0xFF]++;
    }

    double entropy = 0.0;
    for (int count : freq) {
      if (count == 0) {
        continue;
      }
      double p = (double) count / sampleLen;
      entropy -= p * (Math.log(p) / Math.log(2));
    }
    return entropy < HIGH_ENTROPY_CUTOFF;
  }

  // ── SmartDeflater ─────────────────────────────────────────────────────────

  /**
   * Stateful per-pipeline deflater.  Wraps {@link java.util.zip.Deflater} and adds the
   * smart-skip logic from the enclosing {@link SmartCompression} context.
   */
  public static final class SmartDeflater implements AutoCloseable {

    private final Deflater deflater;
    private final int threshold;
    private final SmartCompression ctx;

    SmartDeflater(int level, int threshold, SmartCompression ctx) {
      this.deflater = new Deflater(level == -1 ? Deflater.DEFAULT_COMPRESSION : level);
      this.threshold = threshold;
      this.ctx = ctx;
    }

    /**
     * Attempts to deflate {@code in} into a new buffer.
     *
     * @return the compressed buffer (caller must release), or {@code null} if compression was
     *         skipped (the caller must send the raw payload with an uncompressed-length header of 0)
     */
    public ByteBuf deflate(ByteBufAllocator alloc, ByteBuf in) {
      int rawLen = in.readableBytes();

      if (rawLen < threshold) {
        return null; // below threshold — send raw per vanilla behaviour
      }

      if (!SmartCompression.isLikelyCompressible(in, rawLen)) {
        logger.trace("[Conduit] SmartCompression: skipping high-entropy payload ({} bytes)", rawLen);
        return null;
      }

      byte[] inputArray = new byte[rawLen];
      in.getBytes(in.readerIndex(), inputArray);

      deflater.reset();
      deflater.setInput(inputArray);
      deflater.finish();

      // Deflate into a heap byte[] first so the result is well-defined regardless of whether
      // the allocator returns a direct or heap buffer.
      byte[] outBytes = new byte[rawLen + 32];
      int written = 0;
      while (!deflater.finished()) {
        if (written == outBytes.length) {
          byte[] grown = new byte[outBytes.length * 2];
          System.arraycopy(outBytes, 0, grown, 0, written);
          outBytes = grown;
        }
        written += deflater.deflate(outBytes, written, outBytes.length - written);
      }

      // Skip if savings do not meet the minimum delta.
      int delta = rawLen - written;
      if (delta < ctx.minDeltaBytes) {
        logger.trace("[Conduit] SmartCompression: compressed {} → {} bytes (delta {}), skipping",
            rawLen, written, delta);
        return null;
      }

      ByteBuf out = alloc.buffer(written);
      try {
        out.writeBytes(outBytes, 0, written);
        return out;
      } catch (Exception e) {
        out.release();
        throw e;
      }
    }

    @Override
    public void close() {
      deflater.end();
    }
  }
}
