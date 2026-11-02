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
    return continueHash(FNV_INIT, data, version);
  }

  /**
   * Continues the hash over the UTF-8 bytes of {@code data}, as {@link String#getBytes} would
   * encode them (an unpaired surrogate becomes {@code '?'}), without allocating them.
   */
  public static long continueHash(long currentHash, String data, Version version) {
    boolean v1 = version == Version.v1;
    long hash = currentHash;
    int length = data.length();
    for (int i = 0; i < length; i++) {
      char c = data.charAt(i);
      if (c < 0x80) {
        hash = hashByte(hash, c, v1);
      } else if (c < 0x800) {
        hash = hashByte(hash, 0xc0 | (c >> 6), v1);
        hash = hashByte(hash, 0x80 | (c & 0x3f), v1);
      } else if (!Character.isSurrogate(c)) {
        hash = hashByte(hash, 0xe0 | (c >> 12), v1);
        hash = hashByte(hash, 0x80 | ((c >> 6) & 0x3f), v1);
        hash = hashByte(hash, 0x80 | (c & 0x3f), v1);
      } else if (Character.isHighSurrogate(c)
          && i + 1 < length
          && Character.isLowSurrogate(data.charAt(i + 1))) {
        int codePoint = Character.toCodePoint(c, data.charAt(++i));
        hash = hashByte(hash, 0xf0 | (codePoint >> 18), v1);
        hash = hashByte(hash, 0x80 | ((codePoint >> 12) & 0x3f), v1);
        hash = hashByte(hash, 0x80 | ((codePoint >> 6) & 0x3f), v1);
        hash = hashByte(hash, 0x80 | (codePoint & 0x3f), v1);
      } else {
        hash = hashByte(hash, '?', v1);
      }
    }
    return hash;
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

  private static long hashByte(long hash, int b, boolean v1) {
    return v1 ? (hash * FNV_PRIME) ^ b : (hash ^ b) * FNV_PRIME;
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
