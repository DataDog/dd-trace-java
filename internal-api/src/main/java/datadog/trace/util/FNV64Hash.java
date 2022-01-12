package datadog.trace.util;

/**
 * Calculates the FNV1 64 bit hash. Longs should be treated as though they were unsigned
 *
 * http://www.isthe.com/chongo/tech/comp/fnv/index.html#FNV-1
 */
public class FNV64Hash {
  public static long generateHash(String data) {
    return generateHash(data.getBytes());
  }

  public static long generateHash(byte[] data) {
    return generateHash(data, 0, data.length);
  }

  public static long generateHash(byte[] data, int start, int length) {
    // TODO implement
    return 0;
  }
}
