package com.datadog.profiling.utils.zstd;

import static com.datadog.profiling.utils.zstd.Util.checkPositionIndexes;
import static com.datadog.profiling.utils.zstd.ZstdConstants.SIZE_OF_LONG;
import static java.lang.Long.rotateLeft;
import static java.lang.Math.min;

import datadog.trace.util.UnsafeUtils;

/**
 * xxHash64 implementation for zstd frame checksums. Ported from aircompressor which was forked from
 * airlift/slice.
 */
final class XxHash64 {
  private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
  private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
  private static final long PRIME64_3 = 0x165667B19E3779F9L;
  private static final long PRIME64_4 = 0x85EBCA77C2b2AE63L;
  private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

  private static final long DEFAULT_SEED = 0;

  private final long seed;

  private static final long BUFFER_ADDRESS = UnsafeUtils.BYTE_ARRAY_BASE_OFFSET;
  private final byte[] buffer = new byte[32];
  private int bufferSize;

  private long bodyLength;

  private long v1;
  private long v2;
  private long v3;
  private long v4;

  XxHash64() {
    this(DEFAULT_SEED);
  }

  private XxHash64(long seed) {
    this.seed = seed;
    this.v1 = seed + PRIME64_1 + PRIME64_2;
    this.v2 = seed + PRIME64_2;
    this.v3 = seed;
    this.v4 = seed - PRIME64_1;
  }

  XxHash64 update(byte[] data, int offset, int length) {
    checkPositionIndexes(offset, offset + length, data.length);
    updateHash(data, UnsafeUtils.BYTE_ARRAY_BASE_OFFSET + offset, length);
    return this;
  }

  long hash() {
    long hash;
    if (bodyLength > 0) {
      hash = computeBody();
    } else {
      hash = seed + PRIME64_5;
    }

    hash += bodyLength + bufferSize;

    return updateTail(hash, buffer, BUFFER_ADDRESS, 0, bufferSize);
  }

  private long computeBody() {
    long hash = rotateLeft(v1, 1) + rotateLeft(v2, 7) + rotateLeft(v3, 12) + rotateLeft(v4, 18);

    hash = update(hash, v1);
    hash = update(hash, v2);
    hash = update(hash, v3);
    hash = update(hash, v4);

    return hash;
  }

  private void updateHash(Object base, long address, int length) {
    if (bufferSize > 0) {
      int available = min(32 - bufferSize, length);

      UnsafeUtils.copyMemory(base, address, buffer, BUFFER_ADDRESS + bufferSize, available);

      bufferSize += available;
      address += available;
      length -= available;

      if (bufferSize == 32) {
        updateBody(buffer, BUFFER_ADDRESS, bufferSize);
        bufferSize = 0;
      }
    }

    if (length >= 32) {
      int index = updateBody(base, address, length);
      address += index;
      length -= index;
    }

    if (length > 0) {
      UnsafeUtils.copyMemory(base, address, buffer, BUFFER_ADDRESS, length);
      bufferSize = length;
    }
  }

  private int updateBody(Object base, long address, int length) {
    int remaining = length;
    while (remaining >= 32) {
      v1 = mix(v1, UnsafeUtils.getLong(base, address));
      v2 = mix(v2, UnsafeUtils.getLong(base, address + 8));
      v3 = mix(v3, UnsafeUtils.getLong(base, address + 16));
      v4 = mix(v4, UnsafeUtils.getLong(base, address + 24));

      address += 32;
      remaining -= 32;
    }

    int index = length - remaining;
    bodyLength += index;
    return index;
  }

  static long hash(long value) {
    long hash = DEFAULT_SEED + PRIME64_5 + SIZE_OF_LONG;
    hash = updateTail(hash, value);
    hash = finalShuffle(hash);
    return hash;
  }

  static long hash(long seed, Object base, long address, int length) {
    long hash;
    if (length >= 32) {
      hash = updateBodyStatic(seed, base, address, length);
    } else {
      hash = seed + PRIME64_5;
    }

    hash += length;

    // round to the closest 32 byte boundary
    int index = length & 0xFFFFFFE0;

    return updateTail(hash, base, address, index, length);
  }

  private static long updateTail(long hash, Object base, long address, int index, int length) {
    while (index <= length - 8) {
      hash = updateTail(hash, UnsafeUtils.getLong(base, address + index));
      index += 8;
    }

    if (index <= length - 4) {
      hash = updateTail(hash, UnsafeUtils.getInt(base, address + index));
      index += 4;
    }

    while (index < length) {
      hash = updateTail(hash, UnsafeUtils.getByte(base, address + index));
      index++;
    }

    hash = finalShuffle(hash);
    return hash;
  }

  private static long updateBodyStatic(long seed, Object base, long address, int length) {
    long sv1 = seed + PRIME64_1 + PRIME64_2;
    long sv2 = seed + PRIME64_2;
    long sv3 = seed;
    long sv4 = seed - PRIME64_1;

    int remaining = length;
    while (remaining >= 32) {
      sv1 = mix(sv1, UnsafeUtils.getLong(base, address));
      sv2 = mix(sv2, UnsafeUtils.getLong(base, address + 8));
      sv3 = mix(sv3, UnsafeUtils.getLong(base, address + 16));
      sv4 = mix(sv4, UnsafeUtils.getLong(base, address + 24));

      address += 32;
      remaining -= 32;
    }

    long hash = rotateLeft(sv1, 1) + rotateLeft(sv2, 7) + rotateLeft(sv3, 12) + rotateLeft(sv4, 18);

    hash = update(hash, sv1);
    hash = update(hash, sv2);
    hash = update(hash, sv3);
    hash = update(hash, sv4);

    return hash;
  }

  private static long mix(long current, long value) {
    return rotateLeft(current + value * PRIME64_2, 31) * PRIME64_1;
  }

  private static long update(long hash, long value) {
    long temp = hash ^ mix(0, value);
    return temp * PRIME64_1 + PRIME64_4;
  }

  private static long updateTail(long hash, long value) {
    long temp = hash ^ mix(0, value);
    return rotateLeft(temp, 27) * PRIME64_1 + PRIME64_4;
  }

  private static long updateTail(long hash, int value) {
    long unsigned = value & 0xFFFF_FFFFL;
    long temp = hash ^ (unsigned * PRIME64_1);
    return rotateLeft(temp, 23) * PRIME64_2 + PRIME64_3;
  }

  private static long updateTail(long hash, byte value) {
    int unsigned = value & 0xFF;
    long temp = hash ^ (unsigned * PRIME64_5);
    return rotateLeft(temp, 11) * PRIME64_1;
  }

  private static long finalShuffle(long hash) {
    hash ^= hash >>> 33;
    hash *= PRIME64_2;
    hash ^= hash >>> 29;
    hash *= PRIME64_3;
    hash ^= hash >>> 32;
    return hash;
  }
}
