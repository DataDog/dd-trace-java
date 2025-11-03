package datadog.trace.util;

public final class BitUtils {
  private BitUtils() {}

  /**
   * Returns the next power of two greater than or equal to the given value. If the input is zero or
   * negative, this method returns 1;
   *
   * @param value the input value
   * @return the next power of two ≥ {@code value}
   */
  public static int nextPowerOfTwo(int value) {
    if (value <= 1) {
      return 1;
    }

    // Round up to next power of two (bitwise equivalent of using log2 and pow again)
    value--;
    value |= value >> 1;
    value |= value >> 2;
    value |= value >> 4;
    value |= value >> 8;
    value |= value >> 16;
    value++;

    // handle overflow (e.g., if value was already near Integer.MAX_VALUE)
    if (value <= 0) {
      return 1 << 30; // max power of two that fits in int
    }

    return value;
  }
}
