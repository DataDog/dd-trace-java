package datadog.trace.util;

import java.nio.charset.StandardCharsets;

/**
 * Calculates the FNV 64 bit hash. Longs should be treated as though they were unsigned
 *
 * <p>http://www.isthe.com/chongo/tech/comp/fnv/index.html#FNV-1
 */
public class FNV64Hash {
  private static final long FNV_INIT = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  public enum Version {
    v1,
    v1A
  }

  public static long generateHash(String data, Version version) {
    return generateHash(data.getBytes(StandardCharsets.UTF_8), version);
  }

  public static long continueHash(long currentHash, String data, Version version) {
    return continueHash(currentHash, data.getBytes(StandardCharsets.UTF_8), version);
  }

  public static long generateHash(byte[] data, Version version) {
    return generateHash(data, 0, data.length, version);
  }

  public static long continueHash(long currentHash, byte[] data, Version version) {
    return continueHash(currentHash, data, 0, data.length, version);
  }

  public static long generateHash(byte[] data, int start, int length, Version version) {
    return continueHash(FNV_INIT, data, start, length, version);
  }

  public static long continueHash(
      long currentHash, byte[] data, int start, int length, Version version) {
    if (version == Version.v1) {
      return generateFNV1Hash(currentHash, data, start, length);
    } else {
      return generateFNV1AHash(currentHash, data, start, length);
    }
  }

  /**
   * Same as {@link #generateHash(byte[], Version)} over the 8 little-endian bytes of each value.
   */
  public static long generateHashLongsLE(long first, long second, Version version) {
    return continueHashLongLE(continueHashLongLE(FNV_INIT, first, version), second, version);
  }

  /** Same as {@link #continueHash(long, byte[], Version)} over the 8 little-endian value bytes. */
  public static long continueHashLongLE(long currentHash, long value, Version version) {
    long hash = currentHash;
    if (version == Version.v1) {
      for (int shift = 0; shift < 64; shift += 8) {
        hash *= FNV_PRIME;
        hash ^= (value >>> shift) & 0xffL;
      }
    } else {
      for (int shift = 0; shift < 64; shift += 8) {
        hash ^= (value >>> shift) & 0xffL;
        hash *= FNV_PRIME;
      }
    }
    return hash;
  }

  /** Same as {@link #continueHash(long, byte[], Version)} over the 8 big-endian value bytes. */
  public static long continueHashLongBE(long currentHash, long value, Version version) {
    long hash = currentHash;
    if (version == Version.v1) {
      for (int shift = 56; shift >= 0; shift -= 8) {
        hash *= FNV_PRIME;
        hash ^= (value >>> shift) & 0xffL;
      }
    } else {
      for (int shift = 56; shift >= 0; shift -= 8) {
        hash ^= (value >>> shift) & 0xffL;
        hash *= FNV_PRIME;
      }
    }
    return hash;
  }

  private static long generateFNV1Hash(long currentHash, byte[] data, int start, int length) {
    long hash = currentHash;

    for (int i = start; i < start + length; i++) {
      hash *= FNV_PRIME;
      hash ^= 0xffL & data[i];
    }

    return hash;
  }

  private static long generateFNV1AHash(long currentHash, byte[] data, int start, int length) {
    long hash = currentHash;

    for (int i = start; i < start + length; i++) {
      hash ^= 0xffL & data[i];
      hash *= FNV_PRIME;
    }

    return hash;
  }
}
