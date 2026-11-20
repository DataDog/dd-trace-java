package datadog.context.propagation;

/**
 * Decimal digits of a {@code long} written straight into ASCII bytes, for carrier values such as
 * ids. {@link #of(long)} and {@link #ofUnsigned(long)} return arrays sized exactly to the digits.
 */
public final class AsciiDecimal {
  /** 10^19, the smallest 20-digit unsigned value, as a (negative) {@code long}. */
  private static final long TEN_POW_19 = -8446744073709551616L;

  private static final byte[] TENS = new byte[100];
  private static final byte[] ONES = new byte[100];

  static {
    for (int i = 0; i < 100; i++) {
      TENS[i] = (byte) ('0' + i / 10);
      ONES[i] = (byte) ('0' + i % 10);
    }
  }

  private AsciiDecimal() {}

  /** {@code value} in decimal, as {@link Long#toString(long)} would write it. */
  public static byte[] of(long value) {
    if (value >= 0) {
      byte[] bytes = new byte[length(value)];
      write(bytes, bytes.length, value);
      return bytes;
    }
    // Negating Long.MIN_VALUE leaves it as is, which read as unsigned is its magnitude
    byte[] bytes = new byte[1 + unsignedLength(-value)];
    bytes[0] = '-';
    writeUnsigned(bytes, bytes.length, -value);
    return bytes;
  }

  /** {@code value} in decimal, as {@link Long#toUnsignedString(long)} would write it. */
  public static byte[] ofUnsigned(long value) {
    byte[] bytes = new byte[unsignedLength(value)];
    writeUnsigned(bytes, bytes.length, value);
    return bytes;
  }

  /** The number of digits in {@code value}, read as unsigned. */
  static int unsignedLength(long value) {
    if (value >= 0) {
      return length(value);
    }
    return Long.compareUnsigned(value, TEN_POW_19) >= 0 ? 20 : 19;
  }

  /** The number of digits in {@code nonNegative}. */
  static int length(long nonNegative) {
    long bound = 10;
    for (int digits = 1; digits < 19; digits++) {
      if (nonNegative < bound) {
        return digits;
      }
      bound *= 10;
    }
    return 19;
  }

  /**
   * Writes the digits of {@code value}, read as unsigned, so the last one is before {@code end}.
   */
  static void writeUnsigned(byte[] bytes, int end, long value) {
    if (value < 0) {
      long quotient = (value >>> 1) / 5; // the unsigned value divided by 10
      bytes[--end] = (byte) ('0' + (value - quotient * 10));
      value = quotient;
    }
    write(bytes, end, value);
  }

  /** Writes the digits of {@code nonNegative} so the last one is before {@code end}. */
  static void write(byte[] bytes, int end, long nonNegative) {
    // Two digits per division, and int arithmetic once the value fits
    long value = nonNegative;
    while (value > Integer.MAX_VALUE) {
      long quotient = value / 100;
      int pair = (int) (value - quotient * 100);
      bytes[--end] = ONES[pair];
      bytes[--end] = TENS[pair];
      value = quotient;
    }
    int remaining = (int) value;
    while (remaining >= 100) {
      int quotient = remaining / 100;
      int pair = remaining - quotient * 100;
      bytes[--end] = ONES[pair];
      bytes[--end] = TENS[pair];
      remaining = quotient;
    }
    if (remaining >= 10) {
      bytes[--end] = ONES[remaining];
      bytes[--end] = TENS[remaining];
    } else {
      bytes[--end] = (byte) ('0' + remaining);
    }
  }
}
