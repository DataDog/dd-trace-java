package datadog.trace.api;

import datadog.trace.api.internal.util.HexStringUtils;

/**
 * Class encapsulating the unsigned 64 bit id used for Traceids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 */
public class DD64bTraceId implements DDTraceId {

  public static final DD64bTraceId ZERO = new DD64bTraceId(0, "0", null);
  public static final DD64bTraceId MAX =
      new DD64bTraceId(-1, "18446744073709551615", null); // All bits set

  // Convenience constant used from tests
  public static final DD64bTraceId ONE = DD64bTraceId.from(1);
  private final long id;
  private String str; // cache for string representation
  private String hex; //

  DD64bTraceId(long id, String str, String original) {
    this.id = id;
    this.str = str;
    this.hex = original;
  }

  /**
   * Create a new {@link DD64bTraceId} from the given {@code long} interpreted as the bits of the
   * unsigned 64-bit id. This means that values larger than Long.MAX_VALUE will be represented as
   * negative numbers.
   *
   * @param id The {@code long} representing the bits of the unsigned 64-bit id.
   * @return A new {@link DD64bTraceId} instance.
   */
  public static DD64bTraceId from(long id) {
    return DD64bTraceId.create(id, null);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} representation of the unsigned 64
   * bit id.
   *
   * @param s String of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DD64bTraceId from(String s) throws NumberFormatException {
    return DD64bTraceId.create(DDId.parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DD64bTraceId fromHex(String s) throws NumberFormatException {
    return DD64bTraceId.create(DDId.parseUnsignedLongHex(s), null);
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
  public static DD64bTraceId fromHexTruncatedWithOriginal(String s) throws NumberFormatException {
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
  public static DD64bTraceId fromHexTruncatedWithOriginal(
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
    return new DD64bTraceId(
        HexStringUtils.parseUnsignedLongHex(s, trimmedStart, trimmed, lowerCaseOnly),
        null,
        original);
  }

  static DD64bTraceId create(long id, String str) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DD64bTraceId(id, str, null);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DD64bTraceId)) return false;
    DD64bTraceId ddId = (DD64bTraceId) o;
    return this.id == ddId.id;
  }

  @Override
  public int hashCode() {
    long id = this.id;
    return (int) (id ^ (id >>> 32));
  }

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

  @Override
  public String toHexString() {
    // TODO use the cached String and trim it if necessary
    return Long.toHexString(this.id);
  }

  @Override
  public String toHexStringPadded(int size) {
    // TODO use the cached String and pad it if necessary
    return DDId.toHexStringPadded(this.id, size);
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id, or the
   * original {@code String} used to create this {@code DDId}. The hex {@code String} will NOT be
   * cached.
   *
   * @return non zero padded hex String
   */
  @Override
  public String toHexStringOrOriginal() {
    String h = this.hex;
    if (h == null) {
      this.hex = h = this.toHexString();
    }
    return h;
  }

  /**
   * Returns the zero padded hex representation, in lower case, of the unsigned 64 bit id or the
   * original. The size will be rounded up to 16 or 32 characters. The hex {@code String} will NOT
   * be cached.
   *
   * @param size the size in characters of the 0 padded String (rounded up to 16 or 32)
   * @return zero padded hex String
   */
  @Override
  public String toHexStringPaddedOrOriginal(int size) {
    if (size > 16) {
      size = 32;
    } else if (size < 16) {
      size = 16;
    }
    String h = this.hex;
    if (h == null) {
      h = DDId.toHexStringPadded(this.id, size);
    }
    int len = h.length();
    if (len == size) {
      return h;
    } else if (len > size) {
      return h.substring((len - 1) - size, len);
    } else {
      StringBuilder sb = new StringBuilder(size);
      for (int i = len; i < size; i++) {
        sb.append('0');
      }
      sb.append(h);
      return sb.toString();
    }
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
}
