package datadog.trace.util;

import java.nio.charset.StandardCharsets;

/**
 * Implementation of the MurmurHash2 64-bit hash functions adapted from
 * https://github.com/apache/commons-codec/blob/master/src/main/java/org/apache/commons/codec/digest/MurmurHash2.java
 */
public final class MurmurHash2 {

  // Constants for 64-bit variant
  private static final long M64 = 0xc6a4a7935bd1e995L;
  private static final int R64 = 47;

  /** No instance methods. */
  private MurmurHash2() {}

  /**
   * Generates a 64-bit hash from byte array of the given length and seed.
   *
   * @param data The input byte array
   * @param length The length of the array
   * @param seed The initial seed value
   * @return The 64-bit hash of the given array
   */
  public static long hash64(final byte[] data, final int length, final int seed) {
    long h = (seed & 0xffffffffL) ^ (length * M64);

    final int nblocks = length >> 3;

    // body
    for (int i = 0; i < nblocks; i++) {
      final int index = (i << 3);
      long k = getLittleEndianLong(data, index);

      k *= M64;
      k ^= k >>> R64;
      k *= M64;

      h ^= k;
      h *= M64;
    }

    final int index = (nblocks << 3);
    switch (length - index) {
      case 7:
        h ^= ((long) data[index + 6] & 0xff) << 48;
      case 6:
        h ^= ((long) data[index + 5] & 0xff) << 40;
      case 5:
        h ^= ((long) data[index + 4] & 0xff) << 32;
      case 4:
        h ^= ((long) data[index + 3] & 0xff) << 24;
      case 3:
        h ^= ((long) data[index + 2] & 0xff) << 16;
      case 2:
        h ^= ((long) data[index + 1] & 0xff) << 8;
      case 1:
        h ^= ((long) data[index] & 0xff);
        h *= M64;
    }

    h ^= h >>> R64;
    h *= M64;
    h ^= h >>> R64;

    return h;
  }

  /**
   * Generates a 64-bit hash from byte array with given length and a default seed value. This is a
   * helper method that will produce the same result as:
   *
   * <pre>
   * int seed = 0xe17a1465;
   * int hash = MurmurHash2.hash64(data, length, seed);
   * </pre>
   *
   * @param data The input byte array
   * @param length The length of the array
   * @return The 64-bit hash
   * @see #hash64(byte[], int, int)
   */
  public static long hash64(final byte[] data, final int length) {
    return hash64(data, length, 0xe17a1465);
  }

  /**
   * Generates a 64-bit hash from a string with a default seed.
   *
   * <p>Before 1.14 the string was converted using default encoding. Since 1.14 the string is
   * converted to bytes using UTF-8 encoding. This is a helper method that will produce the same
   * result as:
   *
   * <pre>
   * int seed = 0xe17a1465;
   * byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
   * int hash = MurmurHash2.hash64(bytes, bytes.length, seed);
   * </pre>
   *
   * @param text The input string
   * @return The 64-bit hash
   * @see #hash64(byte[], int, int)
   */
  public static long hash64(final String text) {
    final byte[] bytes = Strings.getBytes(text, StandardCharsets.UTF_8);
    return hash64(bytes, bytes.length);
  }

  /**
   * Generates a 64-bit hash from a substring with a default seed value. The string is converted to
   * bytes using the default encoding. This is a helper method that will produce the same result as:
   *
   * <pre>
   * int seed = 0xe17a1465;
   * byte[] bytes = text.substring(from, from + length).getBytes(StandardCharsets.UTF_8);
   * int hash = MurmurHash2.hash64(bytes, bytes.length, seed);
   * </pre>
   *
   * @param text The The input string
   * @param from The starting index
   * @param length The length of the substring
   * @return The 64-bit hash
   * @see #hash64(byte[], int, int)
   */
  public static long hash64(final String text, final int from, final int length) {
    return hash64(text.substring(from, from + length));
  }

  /**
   * Gets the little-endian int from 4 bytes starting at the specified index.
   *
   * @param data The data
   * @param index The index
   * @return The little-endian int
   */
  private static int getLittleEndianInt(final byte[] data, final int index) {
    return ((data[index] & 0xff))
        | ((data[index + 1] & 0xff) << 8)
        | ((data[index + 2] & 0xff) << 16)
        | ((data[index + 3] & 0xff) << 24);
  }

  /**
   * Gets the little-endian long from 8 bytes starting at the specified index.
   *
   * @param data The data
   * @param index The index
   * @return The little-endian long
   */
  private static long getLittleEndianLong(final byte[] data, final int index) {
    return (((long) data[index] & 0xff))
        | (((long) data[index + 1] & 0xff) << 8)
        | (((long) data[index + 2] & 0xff) << 16)
        | (((long) data[index + 3] & 0xff) << 24)
        | (((long) data[index + 4] & 0xff) << 32)
        | (((long) data[index + 5] & 0xff) << 40)
        | (((long) data[index + 6] & 0xff) << 48)
        | (((long) data[index + 7] & 0xff) << 56);
  }
}
