package datadog.trace.util;

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
    return generateHash(data.getBytes(), version);
  }

  public static long generateHash(byte[] data, Version version) {
    return generateHash(data, 0, data.length, version);
  }

  public static long generateHash(byte[] data, int start, int length, Version version) {
    if (version == Version.v1) {
      return generateFNV1Hash(data, start, length);
    } else {
      return generateFNV1AHash(data, start, length);
    }
  }

  private static long generateFNV1Hash(byte[] data, int start, int length) {
    long hash = FNV_INIT;

    for (int i = start; i < start + length; i++) {
      hash *= FNV_PRIME;
      hash ^= 0xffL & data[i];
    }

    return hash;
  }

  private static long generateFNV1AHash(byte[] data, int start, int length) {
    long hash = FNV_INIT;

    for (int i = start; i < start + length; i++) {
      hash ^= 0xffL & data[i];
      hash *= FNV_PRIME;
    }

    return hash;
  }
}
