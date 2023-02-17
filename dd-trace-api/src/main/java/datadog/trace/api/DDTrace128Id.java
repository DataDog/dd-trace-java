package datadog.trace.api;

import datadog.trace.api.internal.util.HexStringUtils;

/**
 * Class encapsulating the unsigned 128-bit id used for Traceids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 *
 * <p>{@link DDTrace128Id} can represent either a 128-bits TraceId or a 64-bit TraceId. For 128-bit
 * TraceId, {@link #mostSigBits} contains a 32-bit timestamp store on the 32 higher bits and
 * {@link #leastSigBits} contains a unique and random 64-bit id. For 64-bit TraceId, {@link
 * #mostSigBits} is set to <code>0</code> and {@link #leastSigBits} contains a unique and random
 * 63-bit id.
 */
public class DDTrace128Id implements DDTraceId {
  public static final DDTrace128Id ZERO =
      new DDTrace128Id(0, 0, "00000000000000000000000000000000");
  /**
   * Represents the high-order 64 bits of the 128-bit trace id.
   */
  private final long mostSigBits;
  /**
   * Represents the low-order 64 bits of the 128-bit trace id.
   */
  private final long leastSigBits;

  private String str; // cache for string representation

  private DDTrace128Id(long mostSigBits, long leastSigBits, String str) {
    this.mostSigBits = mostSigBits;
    this.leastSigBits = leastSigBits;
    this.str = str;
  }

  /**
   * Create a new 128-bit {@link DDTrace128Id} from the given {@code long}s interpreted as high
   * order and low order bits of the 128-bit id. DDTraceId
   *
   * @param mostSigBits  A {@code long} representing the high-order bits of the {@link DDTrace128Id}.
   * @param leastSigBits A {@code long} representing the random id low-order bits.
   * @return The created trace id instance.
   */
  public static DDTrace128Id from(long mostSigBits, long leastSigBits) {
    return new DDTrace128Id(mostSigBits, leastSigBits, null);
  }

  /**
   * Create a {@link DDTrace128Id} from a TraceId {@link String} representation. Both 64-bit
   * unsigned id and 32 hexadecimal lowercase characters are allowed.
   *
   * @param s The TraceId {@link String} representation to parse.
   * @return The parsed TraceId.
   * @throws IllegalArgumentException if the string to parse is not valid.
   */
  // TODO Define what expected behavior is (which format should be okay to be parsed?)
  public static DDTrace128Id from(String s) throws IllegalArgumentException {
    if (s == null) {
      throw new IllegalArgumentException("s can't be null");
    }
    if (s.length() == 32) {
      long highOrderBits = HexStringUtils.parseUnsignedLongHex(s, 0, 16, false);
      long id = HexStringUtils.parseUnsignedLongHex(s, 16, 16, false);
      return new DDTrace128Id(highOrderBits, id, s);
    }
    long id = DDId.parseUnsignedLong(s);
    return new DDTrace128Id(0, id, s);
  }

  @Override
  public long toLong() {
    return this.leastSigBits;
  }

  @Override
  public String toHexString() {
    return toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DDTrace128Id)) return false;
    DDTrace128Id ddId = (DDTrace128Id) o;
    return this.mostSigBits == ddId.mostSigBits && this.leastSigBits == ddId.leastSigBits;
  }

  @Override
  public int hashCode() {
    long id = this.leastSigBits;
    return (int) (id ^ (id >>> 32));
  }

  @Override
  public String toString() {
    String s = this.str;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (s == null) {
      this.str =
          s =
              DDId.toHexStringPadded(this.mostSigBits, 16)
                  + DDId.toHexStringPadded(this.leastSigBits, 16);
    }
    return s;
  }
}
