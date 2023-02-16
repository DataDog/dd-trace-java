package datadog.trace.api;

/**
 * Class encapsulating the id used for TraceIds.
 *
 * <p>It contains generation of new ids, parsing, and to string hex representations. The string
 * representation is either kept from parsing, or generated on demand and cached.
 */
public interface DDTraceId {
  DDTraceId ZERO = DDTrace64Id.ZERO;
  /**
   * Create a {@link DDTraceId} from a TraceId {@link String} representation.
   *
   * @param s The TraceId {@link String} representation to parse.
   * @return The parsed TraceId.
   * @throws IllegalArgumentException if the string to parse is not valid.
   */
  static DDTraceId from(String s) throws IllegalArgumentException {
    if (s == null) {
      throw new IllegalArgumentException("s can't be null");
    }
    if (s.length() == 32) {
      return DDTrace128Id.from(s);
    }
    return DDTrace64Id.create(DDId.parseUnsignedLong(s), s);
  }

  /**
   * Returns the unsigned 64 bits random id part of the TraceId as a <code>long</code>. This means
   * that values larger than {@link Long#MAX_VALUE} will be represented as negative numbers.
   *
   * @return the unsigned 64 bits random id part of the TraceId.
   */
  long toLong();

  /**
   * Returns the hexadecimal representation of the TraceId.
   *
   * @return The hexadecimal representation of the TraceId.
   */
  String toHexString();
}
