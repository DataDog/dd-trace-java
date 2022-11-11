package datadog.trace.api;

/** Class encapsulating the unsigned 64 bit id used for Span ids. */
public class DDSpanId extends DDId {

  public static final DDSpanId ZERO = new DDSpanId(0, "0");
  public static final DDSpanId MAX = new DDSpanId(-1, "18446744073709551615"); // All bits set

  // Convenience constant used from tests
  public static final DDSpanId ONE = DDSpanId.from(1);

  /**
   * Create a new {@code DDSpanId} from the given {@code long} interpreted as the bits of the
   * unsigned 64 bit id. This means that values larger than Long.MAX_VALUE will be represented as
   * negative numbers.
   *
   * @param id long representing the bits of the unsigned 64 bit id
   * @return DDSpanId
   */
  public static DDSpanId from(long id) {
    return DDSpanId.create(id, null);
  }

  /**
   * Create a new {@code DDSpanId} from the given {@code String} representation of the unsigned 64
   * bit id.
   *
   * @param s String of unsigned 64 bit id
   * @return DDSpanId
   * @throws NumberFormatException
   */
  public static DDSpanId from(String s) throws NumberFormatException {
    return DDSpanId.create(parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@code DDSpanId} from the given {@code String} hex representation of the unsigned
   * 64 bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDSpanId
   * @throws NumberFormatException
   */
  public static DDSpanId fromHex(String s) throws NumberFormatException {
    return DDSpanId.create(parseUnsignedLongHex(s), null);
  }

  /**
   * Create a new {@code DDSpanId} from the given {@code String} hex representation of the unsigned
   * 64 bit id, while retalining the original {@code String} representation for use in headers.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDSpanId
   * @throws NumberFormatException
   */
  public static DDSpanId fromHexWithOriginal(String s) throws NumberFormatException {
    return new DDIdOriginal(parseUnsignedLongHex(s), s);
  }

  private static DDSpanId create(long id, String str) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DDSpanId(id, str);
  }

  private DDSpanId(long id, String str) {
    super(id, str);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DDSpanId)) return false;
    DDSpanId ddId = (DDSpanId) o;
    return this.id == ddId.id;
  }

  private static final class DDIdOriginal extends DDSpanId {
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
