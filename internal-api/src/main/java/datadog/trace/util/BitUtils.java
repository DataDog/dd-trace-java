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
    // The next power of two for 0 or 1 is 1.
    if (value <= 1) {
      return 1;
    }

    // Compute how many leading zero bits there are in (value - 1).  This gives us information about
    // where the highest set bit is.
    int n = Integer.numberOfLeadingZeros(value - 1);

    //  -1 in two's complement = 0xFFFF_FFFF (all bits set to 1). Unsigned right-shifting by n: (-1
    // >>> n) produces a mask of (32 - n) one-bits.
    int result = (-1 >>> n) + 1;

    // If result overflowed clamp it to the largest unsigned power of two fitting an int.
    if (result <= 0) {
      return 1 << 30;
    }

    return result;
  }
}
