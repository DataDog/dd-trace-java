package datadog.trace.api;

/** Class encapsulating the unsigned 64 bit id used for Trace ids. */
public class DDTraceId extends DDId {

  public static final DDTraceId ZERO = new DDTraceId(0, "0");
  public static final DDTraceId MAX = new DDTraceId(-1, "18446744073709551615"); // All bits set

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
    return DDTraceId.create(parseUnsignedLong(s), s);
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
    return DDTraceId.create(parseUnsignedLongHex(s), null);
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
    return new DDIdOriginal(parseUnsignedLongHex(s, len - trimmed, trimmed), s);
  }

  private static DDTraceId create(long id, String str) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DDTraceId(id, str);
  }

  private DDTraceId(long id, String str) {
    super(id, str);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DDTraceId)) return false;
    DDTraceId ddId = (DDTraceId) o;
    return this.id == ddId.id;
  }

  private static final class DDIdOriginal extends DDTraceId {
    private final String original;

    private DDIdOriginal(long id, String original) {
      super(id, null);
      this.original = original;
    }

    @Override
    public String toHexStringOrOriginal() {
      return this.original;
    }
  }
}
