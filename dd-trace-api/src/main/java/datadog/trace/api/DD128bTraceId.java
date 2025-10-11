package datadog.trace.api;

import datadog.trace.api.internal.util.LongStringUtils;
import java.util.Locale;

/**
 * Class encapsulating the unsigned 128-bit id used for TraceIds.
 *
 * <p>It contains parsing and formatting to string for both decimal and hexadecimal representations.
 * The string representations are either kept from parsing, or generated on demand and cached.
 *
 * <p>{@link DD128bTraceId} can represent either a 128-bit TraceId or a 64-bit TraceId. For 128-bit
 * TraceId, {@link #highOrderBits} contains a 32-bit timestamp store on the 32 higher bits and
 * {@link #lowOrderBits} contains a unique and random 64-bit id. For 64-bit TraceId, {@link
 * #highOrderBits} is set to <code>0</code> and {@link #lowOrderBits} contains a unique and random
 * 63-bit id.
 */
public class DD128bTraceId extends DDTraceId {
  public static final DD128bTraceId ZERO =
      new DD128bTraceId(0, 0, "00000000000000000000000000000000");

  /** Represents the high-order 64 bits of the 128-bit trace id. */
  private final long highOrderBits;

  /** Represents the low-order 64 bits of the 128-bit trace id. */
  private final long lowOrderBits;

  /**
   * The lower-case, zero-padded, 32 hexadecimal characters {@link String} representation of the
   * {@link DDTraceId} instance.
   */
  private String hexStr;

  /** The 64-bit only decimal {@link String} representation of the {@link DDTraceId} instance. */
  private String str;

  private DD128bTraceId(long highOrderBits, long leastSigBits, String hexStr) {
    this.highOrderBits = highOrderBits;
    this.lowOrderBits = leastSigBits;
    this.hexStr = hexStr;
  }

  /**
   * Create a new 128-bit {@link DD128bTraceId} from the given {@code long}s interpreted as high
   * order and low order bits of the 128-bit id.
   *
   * @param highOrderBits A {@code long} representing the high-order bits of the {@link
   *     DD128bTraceId}.
   * @param lowOrderBits A {@code long} representing the random id low-order bits.
   * @return The created TraceId instance.
   */
  public static DD128bTraceId from(long highOrderBits, long lowOrderBits) {
    return new DD128bTraceId(highOrderBits, lowOrderBits, null);
  }

  /**
   * Create a new 128-bit {@link DD128bTraceId} from the given hexadecimal {@link String}
   * representation.
   *
   * @param s The hexadecimal {@link String} representation to parse (a 32 lower-case hexadecimal
   *     characters maximum).
   * @return The created TraceId instance.
   * @throws NumberFormatException If the hexadecimal {@link String} representation is not valid.
   */
  public static DD128bTraceId fromHex(String s) throws NumberFormatException {
    return fromHex(s, 0, s == null ? 0 : s.length(), true);
  }

  /**
   * Create a new 128-bit {@link DD128bTraceId} from the given hexadecimal {@link String}
   * representation.
   *
   * @param s The string containing the hexadecimal {@link String} representation to parse (32 lower
   *     or higher-case hexadecimal characters maximum).
   * @param start The start index of the hexadecimal {@link String} representation to parse.
   * @param length The length of the hexadecimal {@link String} representation to parse.
   * @param lowerCaseOnly Whether the hexadecimal characters to parse are lower-case only or not.
   * @return The created TraceId instance.
   * @throws NumberFormatException If the hexadecimal {@link String} representation is not valid.
   */
  public static DD128bTraceId fromHex(String s, int start, int length, boolean lowerCaseOnly)
      throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s can't be null");
    }
    int stringLength = s.length();
    if (start < 0 || length <= 0 || length > 32 || start + length > stringLength) {
      throw new NumberFormatException("Illegal start or length");
    }
    // Parse high and low order bits
    long highOrderBits, lowOrderBits;
    if (length > 16) {
      int highOrderLength = length - 16;
      highOrderBits =
          LongStringUtils.parseUnsignedLongHex(s, start, highOrderLength, lowerCaseOnly);
      lowOrderBits =
          LongStringUtils.parseUnsignedLongHex(s, start + highOrderLength, 16, lowerCaseOnly);
    } else {
      highOrderBits = 0;
      lowOrderBits = LongStringUtils.parseUnsignedLongHex(s, start, length, lowerCaseOnly);
    }
    // Extract hexadecimal string representation to cache
    String hexStr = null;
    if (length == 32) {
      if (start == 0) {
        hexStr = s;
      } else {
        hexStr = s.substring(start, start + 32);
      }
      if (!lowerCaseOnly) {
        hexStr = hexStr.toLowerCase(Locale.ROOT);
      }
    }
    return new DD128bTraceId(highOrderBits, lowOrderBits, hexStr);
  }

  /**
   * Returns the lower-case zero-padded 32 hexadecimal characters {@link String} representation of
   * the {@link DD128bTraceId}.
   *
   * @return A lower-case zero-padded 32 hexadecimal characters {@link String} representation of the
   *     {@link DD128bTraceId} instance.
   */
  @Override
  public String toHexString() {
    String hexString = this.hexStr;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (hexString == null) {
      this.hexStr =
          hexString = LongStringUtils.toHexStringPadded(this.highOrderBits, this.lowOrderBits, 32);
    }
    return hexString;
  }

  @Override
  public String toHexStringPadded(int size) {
    if (size <= 16) {
      return LongStringUtils.toHexStringPadded(this.lowOrderBits, 16);
    }
    return toHexString();
  }

  @Override
  public long toLong() {
    return this.lowOrderBits;
  }

  @Override
  public long toHighOrderLong() {
    return this.highOrderBits;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DD128bTraceId)) return false;
    DD128bTraceId ddId = (DD128bTraceId) o;
    return this.highOrderBits == ddId.highOrderBits && this.lowOrderBits == ddId.lowOrderBits;
  }

  @Override
  public int hashCode() {
    return (int)
        (this.highOrderBits
            ^ (this.highOrderBits >>> 32)
            ^ this.lowOrderBits
            ^ (this.lowOrderBits >>> 32));
  }

  @Override
  public String toString() {
    String s = this.str;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (s == null) {
      this.str = s = Long.toUnsignedString(this.lowOrderBits);
    }
    return s;
  }
}
