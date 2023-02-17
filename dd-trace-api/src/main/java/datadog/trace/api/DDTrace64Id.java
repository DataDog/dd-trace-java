package datadog.trace.api;

import datadog.trace.api.internal.util.HexStringUtils;

/**
 * Class encapsulating the unsigned 128-bit id used for Traceids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 *
 * <p>{@link DDTrace64Id} can represent either a 128-bits TraceId or a 64-bit TraceId. For 128-bit
 * TraceId, {@link #highOrderBits} contains a 32-bit timestamp store on the 32 higher bits and
 * {@link #id} contains a unique and random 64-bit id. For 64-bit TraceId, {@link #highOrderBits} is
 * set to <code>0</code> and {@link #id} contains a unique and random 63-bit id.
 */
public class DDTrace64Id implements DDTraceId {
  public static final DDTrace64Id ZERO = new DDTrace64Id(0, "0", "0000000000000000");
  public static final DDTrace64Id MAX =
      new DDTrace64Id(-1, "18446744073709551615", null); // All bits set

  // Convenience constant used from tests
  public static final DDTrace64Id ONE = DDTrace64Id.from(1);

  /**
   * Create a new {@code DDTraceId} from the given {@code long} interpreted as the bits of the
   * unsigned 64-bit id. This means that values larger than Long.MAX_VALUE will be represented as
   * negative numbers.
   *
   * @param id long representing the bits of the unsigned 64-bit id
   * @return DDTraceId
   */
  public static DDTrace64Id from(long id) {
    return DDTrace64Id.create(id, null, null);
  }

  /**
   * Create a {@link DDTrace64Id} from a TraceId {@link String} representation. Both 64-bit unsigned
   * id and 32 hexadecimal lowercase characters are allowed.
   *
   * @param s The TraceId {@link String} representation to parse.
   * @return The parsed TraceId.
   * @throws IllegalArgumentException if the string to parse is not valid.
   */
  public static DDTrace64Id from(String s) throws IllegalArgumentException {
    if (s == null) {
      throw new IllegalArgumentException("s can't be null");
    }
    return DDTrace64Id.create(DDId.parseUnsignedLong(s), s, null);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DDTrace64Id fromHex(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }
    String hex = s.length() == 16 ? s : null;
    return DDTrace64Id.create(DDId.parseUnsignedLongHex(s), null, hex);
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
  public static DDTrace64Id fromHexTruncatedWithOriginal(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    int len = s.length();
    int trimmed = Math.min(s.length(), 16);
    // TODO Set s to to with a 16 char length or remove the method
    return new DDTrace64Id(
        HexStringUtils.parseUnsignedLongHex(s, len - trimmed, trimmed, false), null, s);
  }

  static DDTrace64Id create(long id, String str, String hex) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DDTrace64Id(id, str, hex);
  }

  private final long id;

  private String str; // cache for string representation
  private String hex; // cache for hex representation

  private DDTrace64Id(long id, String str, String hex) {
    this.id = id;
    this.str = str;
    this.hex = hex;
  }

  /**
   * Returns the id as a long representing the bits of the unsigned 64 bit id. This means that
   * values larger than Long.MAX_VALUE will be represented as negative numbers.
   *
   * @return long value representing the bits of the unsigned 64 bit id.
   */
  @Override
  public long toLong() {
    return this.id;
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id. The
   * hex {@code String} will NOT be cached.
   *
   * @return non zero padded hex String
   */
  @Override
  public String toHexString() {
    String hex = this.hex;
    if (hex == null) {
      this.hex = hex = DDId.toHexStringPadded(this.id, 16);
    }
    return hex;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DDTrace64Id)) return false;
    DDTrace64Id ddId = (DDTrace64Id) o;
    return this.id == ddId.id;
  }

  @Override
  public int hashCode() {
    long id = this.id;
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
      this.str = s = Long.toUnsignedString(this.id);
    }
    return s;
  }

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
  //    return DDId.toHexStringPadded(this.id, size);
  //  }
  //
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
}
