package datadog.trace.api;

import datadog.trace.api.internal.util.HexStringUtils;

/**
 * Class encapsulating the unsigned 128-bit id used for Traceids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 *
 * <p>{@link DDTraceId} can represent either a 128-bits TraceId or a 64-bit TraceId. For 128-bit
 * TraceId, {@link #highOrderBits} contains a 32-bit timestamp store on the 32 higher bits and
 * {@link #lowOrderBits} contains a unique and random 64-bit id. For 64-bit TraceId, {@link
 * #highOrderBits} is set to <code>0</code> and {@link #lowOrderBits} contains a unique and random
 * 63-bit id.
 */
public class DDTraceId {
  public static final DDTraceId ZERO = new DDTraceId(0, 0, "0", null);
  public static final DDTraceId MAX =
      new DDTraceId(0, -1, "18446744073709551615", null); // All bits set

  // Convenience constant used from tests
  public static final DDTraceId ONE = DDTraceId.from(1);

  /**
   * Create a new {@code DDTraceId} from the given {@code long} interpreted as the bits of the
   * unsigned 64-bit id. This means that values larger than Long.MAX_VALUE will be represented as
   * negative numbers.
   *
   * @param id long representing the bits of the unsigned 64-bit id
   * @return DDTraceId
   */
  public static DDTraceId from(long id) {
    return DDTraceId.create(0, id, null);
  }

  /**
   * Create a new 128-bit {@link DDTraceId} from the given {@code long}s interpreted as high order
   * and low order bits of the 128-bit id.
   *
   * @param highOrderBits long representing the high-order bits of the {@link DDTraceId}.
   * @param lowOrderBits long representing the random id low-order bits.
   * @return DDTraceId
   */
  public static DDTraceId from(long highOrderBits, long lowOrderBits) {
    return DDTraceId.create(highOrderBits, lowOrderBits, null);
  }

  /**
   * Create a {@link DDTraceId} from a TraceId {@link String} representation. Both 64-bit unsigned
   * id and 32 hexadecimal lowercase characters are allowed.
   *
   * @param s The TraceId {@link String} representation to parse.
   * @return The parsed TraceId.
   * @throws IllegalArgumentException if the string to parse is not valid.
   */
  public static DDTraceId from(String s) throws IllegalArgumentException {
    if (s == null) {
      throw new IllegalArgumentException("s can't be null");
    }
    if (s.length() == 32) {
      return DDTraceId.create(
          HexStringUtils.parseUnsignedLongHex(s, 0, 16, false),
          HexStringUtils.parseUnsignedLongHex(s, 16, 16, false),
          s);
    }
    return DDTraceId.create(0, DDId.parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DDTraceId fromHex(String s)
      throws NumberFormatException { // TODO Add 128-bits support
    return DDTraceId.create(0, DDId.parseUnsignedLongHex(s), null);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit (or more) id truncated to 64 bits, while retaining the original {@code String}
   * representation for use in headers.
   *
   * @param s String in hex of unsigned 64 bit (or more) id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DDTraceId fromHexTruncatedWithOriginal(String s)
      throws NumberFormatException { // TODO Add 128-bits support
    if (s == null) {
      throw new NumberFormatException("null");
    }
    return fromHexTruncatedWithOriginal(s, 0, s.length(), false);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit (or more) id truncated to 64 bits, while retaining the original {@code String}
   * representation for use in headers.
   *
   * @param s String in hex of unsigned 64 bit (or more) id
   * @param start the start index of the hex value
   * @param len the len of the hex value
   * @param lowerCaseOnly if the allowed hex characters are lower case only
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DDTraceId fromHexTruncatedWithOriginal(
      String s, int start, int len, boolean lowerCaseOnly) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }
    int size = s.length();
    if (start < 0 || len <= 0 || len > 32 || start + len > size) {
      throw new NumberFormatException("Illegal start or length");
    }
    int trimmed = Math.min(len, 16);
    int end = start + len;
    int trimmedStart = end - trimmed;
    if (trimmedStart > 0) {
      // we need to check that the characters we don't parse are valid
      HexStringUtils.parseUnsignedLongHex(s, start, trimmedStart, lowerCaseOnly);
    }
    String original;
    if (start == 0 && end == len) {
      original = s;
    } else {
      original = s.substring(start, end);
    }
    return new DDTraceId(
        0,
        HexStringUtils.parseUnsignedLongHex(s, trimmedStart, trimmed, lowerCaseOnly),
        null,
        original);
  }

  private static DDTraceId create(long highOrderBits, long id, String str) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DDTraceId(highOrderBits, id, str, null);
  }

  /** Represents the high-order 64 bits of the 128-bit {@link DDTraceId}. */
  private final long highOrderBits;
  /** Represents the low-order 64 bits of the 128-bit {@link DDTraceId}. */
  private final long lowOrderBits;

  private String str; // cache for string representation
  private String hex; //

  private DDTraceId(long highOrderBits, long lowOrderBits, String str, String original) {
    this.highOrderBits = highOrderBits;
    this.lowOrderBits = lowOrderBits;
    this.str = str;
    this.hex = original;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DDTraceId)) return false;
    DDTraceId ddId = (DDTraceId) o;
    return this.highOrderBits == ddId.highOrderBits && this.lowOrderBits == ddId.lowOrderBits;
  }

  @Override
  public int hashCode() {
    long id = this.lowOrderBits;
    return (int) (id ^ (id >>> 32));
  }

  /**
   * Returns the {@link String} representation of the TraceId. The string representation depends on
   * the TraceId type. For 64-bit TraceId, it is an unsigned 64-bit id. For 128-bit TraceId, it is a
   * 32 lowercase hexadecimal characters. The {@link String} representation will be cached.
   *
   * @return The {@link String} representation of the TraceId.
   */
  @Override
  public String toString() {
    String s = this.str;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (s == null) {
      if (this.highOrderBits == 0L) {
        this.str = s = Long.toUnsignedString(this.lowOrderBits);
      } else {
        this.str =
            s =
                DDId.toHexStringPadded(this.highOrderBits, 16)
                    + DDId.toHexStringPadded(this.lowOrderBits, 16);
      }
    }
    return s;
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id. The
   * hex {@code String} will NOT be cached.
   *
   * @return non zero padded hex String
   */
  public String toHexString() {
    // TODO use the cached String and trim it if necessary
    return Long.toHexString(this.lowOrderBits);
  }

  /**
   * Returns the zero padded hex representation, in lower case, of the unsigned 64 bit id. The size
   * will be rounded up to 16 or 32 characters. The hex {@code String} will NOT be cached.
   *
   * @param size the size in characters of the 0 padded String (rounded up to 16 or 32)
   * @return zero padded hex String
   */
  public String toHexStringPadded(int size) {
    // TODO use the cached String and pad it if necessary
    return DDId.toHexStringPadded(this.lowOrderBits, size);
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id, or the
   * original {@code String} used to create this {@code DDId}. The hex {@code String} will NOT be
   * cached.
   *
   * @return non zero padded hex String
   */
  public String toHexStringOrOriginal() {
    String h = this.hex;
    if (h == null) {
      this.hex = h = this.toHexString();
    }
    return h;
  }

  /**
   * Returns the id as a long representing the bits of the unsigned 64 bit id. This means that
   * values larger than Long.MAX_VALUE will be represented as negative numbers.
   *
   * @return long value representing the bits of the unsigned 64 bit id.
   */
  public long toLong() {
    return this.lowOrderBits;
  }
}
