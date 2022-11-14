package datadog.trace.api;

/** Class with methods for working with the unsigned 64 bit id used for Span ids. */
public final class DDSpanId {

  // Don't allow instances
  private DDSpanId() {}

  /** The ZERO span id is not allowed and means no span. */
  public static final long ZERO = 0;

  // All bits set, only used from tests
  public static final long MAX = -1;

  /**
   * Parse the span id from the given {@code String} representation of the unsigned 64 bit id.
   *
   * @param s String of unsigned 64 bit id
   * @return long
   * @throws NumberFormatException
   */
  public static long from(String s) throws NumberFormatException {
    return DDId.parseUnsignedLong(s);
  }

  /**
   * Parse the span id from the given {@code String} hex representation of the unsigned 64 bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return long
   * @throws NumberFormatException
   */
  public static long fromHex(String s) throws NumberFormatException {
    return DDId.parseUnsignedLongHex(s);
  }

  /**
   * Returns the decimal string representation of the unsigned 64 bit id. The {@code String} will
   * NOT be cached.
   *
   * @param id the long 64 bit id to generate a String for
   * @return decimal string
   */
  public static String toString(long id) {
    // TODO Cache here? https://github.com/DataDog/dd-trace-java/issues/4236
    return Long.toUnsignedString(id);
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id. The
   * hex {@code String} will NOT be cached.
   *
   * @return non zero padded hex String
   */
  public static String toHexString(long id) {
    // TODO Cache here? https://github.com/DataDog/dd-trace-java/issues/4236
    return Long.toHexString(id);
  }

  /**
   * Returns the zero padded hex representation, in lower case, of the unsigned 64 bit id. The size
   * will be rounded up to 16 characters. The hex {@code String} will NOT be cached.
   *
   * @return zero padded hex String
   */
  public static String toHexStringPadded(long id) {
    // TODO Cache here? https://github.com/DataDog/dd-trace-java/issues/4236
    return DDId.toHexStringPadded(id, 16);
  }
}
