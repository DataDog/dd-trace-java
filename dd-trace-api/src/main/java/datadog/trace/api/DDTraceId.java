package datadog.trace.api;

/**
 * Class encapsulating the unsigned 64 bit id used for Traceids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 */
public class DDTraceId {

  public static final DDTraceId ZERO = new DDTraceId(0, "0", null);
  public static final DDTraceId MAX =
      new DDTraceId(-1, "18446744073709551615", null); // All bits set

  // Convenience constant used from tests
  public static final DDTraceId ONE = DDTraceId.from(1);

  /**
   * Create a new {@code DDTraceId} from the given {@code long} interpreted as the bits of the
   * unsigned 64 bit id. This means that values larger than Long.MAX_VALUE will be represented as
   * negative numbers.
   *
   * @param id long representing the bits of the unsigned 64 bit id
   * @return DDTraceId
   */
  public static DDTraceId from(long id) {
    return DDTraceId.create(id, null);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} representation of the unsigned 64
   * bit id.
   *
   * @param s String of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DDTraceId from(String s) throws NumberFormatException {
    return DDTraceId.create(DDId.parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DDTraceId fromHex(String s) throws NumberFormatException {
    return DDTraceId.create(DDId.parseUnsignedLongHex(s), null);
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
  public static DDTraceId fromHexTruncatedWithOriginal(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    int len = s.length();
    int trimmed = Math.min(s.length(), 16);
    return new DDTraceId(DDId.parseUnsignedLongHex(s, len - trimmed, trimmed), null, s);
  }

  private static DDTraceId create(long id, String str) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DDTraceId(id, str, null);
  }

  private final long id;
  private String str; // cache for string representation
  private String hex; //

  private DDTraceId(long id, String str, String original) {
    this.id = id;
    this.str = str;
    this.hex = original;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DDTraceId)) return false;
    DDTraceId ddId = (DDTraceId) o;
    return this.id == ddId.id;
  }

  @Override
  public int hashCode() {
    long id = this.id;
    return (int) (id ^ (id >>> 32));
  }

  /**
   * Returns the decimal string representation of the unsigned 64 bit id. The {@code String} will be
   * cached.
   *
   * @return decimal string
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

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id. The
   * hex {@code String} will NOT be cached.
   *
   * @return non zero padded hex String
   */
  public String toHexString() {
    // TODO use the cached String and trim it if necessary
    return Long.toHexString(this.id);
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
    return DDId.toHexStringPadded(this.id, size);
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
    return this.id;
  }
}
