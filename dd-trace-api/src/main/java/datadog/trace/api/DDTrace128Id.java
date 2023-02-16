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
 * TraceId, {@link #highOrderBits} contains a 32-bit timestamp store on the 32 higher bits and
 * {@link #lowOrderBits} contains a unique and random 64-bit id. For 64-bit TraceId, {@link
 * #highOrderBits} is set to <code>0</code> and {@link #lowOrderBits} contains a unique and random
 * 63-bit id.
 */
public class DDTrace128Id implements DDTraceId {
  public static final DDTrace128Id ZERO =
      new DDTrace128Id(0, 0, "00000000000000000000000000000000");
  /**
   * Create a new 128-bit {@link DDTrace128Id} from the given {@code long}s interpreted as high
   * order and low order bits of the 128-bit id. DDTraceId
   *
   * @param highOrderBits long representing the high-order bits of the {@link DDTrace128Id}.
   * @param lowOrderBits long representing the random id low-order bits.
   * @return DDTraceId
   */
  public static DDTrace128Id from(long highOrderBits, long lowOrderBits) {
    return DDTrace128Id.create(highOrderBits, lowOrderBits, null);
  }

  /**
   * Create a {@link DDTrace128Id} from a TraceId {@link String} representation. Both 64-bit
   * unsigned id and 32 hexadecimal lowercase characters are allowed.
   *
   * @param s The TraceId {@link String} representation to parse.
   * @return The parsed TraceId.
   * @throws IllegalArgumentException if the string to parse is not valid.
   */
  public static DDTrace128Id from(String s) throws IllegalArgumentException {
    if (s == null) {
      throw new IllegalArgumentException("s can't be null");
    }
    if (s.length() == 32) {
      return DDTrace128Id.create(
          HexStringUtils.parseUnsignedLongHex(s, 0, 16, false),
          HexStringUtils.parseUnsignedLongHex(s, 16, 16, false),
          s);
    }
    return DDTrace128Id.create(0, DDId.parseUnsignedLong(s), s);
  }

  //  /**
  //   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the
  // unsigned
  //   * 64 bit (or more) id truncated to 64 bits, while retaining the original {@code String}
  //   * representation for use in headers.
  //   *
  //   * @param s String in hex of unsigned 64 bit (or more) id
  //   * @return DDTraceId
  //   * @throws NumberFormatException
  //   */
  //  public static DDTraceId fromHexTruncatedWithOriginal(String s)
  //      throws NumberFormatException { // TODO Add 128-bits support
  //    if (s == null) {
  //      throw new NumberFormatException("null");
  //    }
  //
  //    int len = s.length();
  //    int trimmed = Math.min(s.length(), 16);
  //    return new DDTraceId(0, DDId.parseUnsignedLongHex(s, len - trimmed, trimmed), null, s);
  //  }

  static DDTrace128Id create(long highOrderBits, long id, String str) {
    return new DDTrace128Id(highOrderBits, id, str);
  }

  /** Represents the high-order 64 bits of the 128-bit {@link DDTrace128Id}. */
  private final long highOrderBits;
  /** Represents the low-order 64 bits of the 128-bit {@link DDTrace128Id}. */
  private final long lowOrderBits;

  private String str; // cache for string representation

  private DDTrace128Id(long highOrderBits, long lowOrderBits, String str) {
    this.highOrderBits = highOrderBits;
    this.lowOrderBits = lowOrderBits;
    this.str = str;
  }

  @Override
  public long toLong() {
    return this.lowOrderBits;
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
    return this.highOrderBits == ddId.highOrderBits && this.lowOrderBits == ddId.lowOrderBits;
  }

  @Override
  public int hashCode() {
    long id = this.lowOrderBits;
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
              DDId.toHexStringPadded(this.highOrderBits, 16)
                  + DDId.toHexStringPadded(this.lowOrderBits, 16);
    }
    return s;
  }

  //  /**
  //   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id.
  // The
  //   * hex {@code String} will NOT be cached.
  //   *
  //   * @return non zero padded hex String
  //   */
  //  public String toHexString() {
  //    // TODO use the cached String and trim it if necessary
  //    return Long.toHexString(this.lowOrderBits);
  //  }

  //  /**
  //   * Returns the zero padded hex representation, in lower case, of the unsigned 64 bit id. The
  // size
  //   * will be rounded up to 16 or 32 characters. The hex {@code String} will NOT be cached.
  //   *
  //   * @param size the size in characters of the 0 padded String (rounded up to 16 or 32)
  //   * @return zero padded hex String
  //   */
  //  public String toHexStringPadded(int size) {
  //    // TODO use the cached String and pad it if necessary
  //    return DDId.toHexStringPadded(this.lowOrderBits, size);
  //  }

  //  /**
  //   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id, or
  // the
  //   * original {@code String} used to create this {@code DDId}. The hex {@code String} will NOT
  // be
  //   * cached.
  //   *
  //   * @return non zero padded hex String
  //   */
  //  public String toHexStringOrOriginal() {
  //    String h = this.hex;
  //    if (h == null) {
  //      this.hex = h = this.toHexString();
  //    }
  //    return h;
  //  }

  //  /**
  //   * Returns the id as a long representing the bits of the unsigned 64 bit id. This means that
  //   * values larger than Long.MAX_VALUE will be represented as negative numbers.
  //   *
  //   * @return long value representing the bits of the unsigned 64 bit id.
  //   */
  //  public long toLong() {
  //    return this.lowOrderBits;
  //  }
}
