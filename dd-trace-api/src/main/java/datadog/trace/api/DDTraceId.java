package datadog.trace.api;

/**
 * Class encapsulating the id used for TraceIds.
 *
 * <p>It contains parsing and formatting to string for both decimal and hexadecimal representations.
 * The string representations are either kept from parsing, or generated on demand and cached.
 */
public interface DDTraceId {
  /** Invalid TraceId value used to denote no TraceId. */
  DDTraceId ZERO = from(0);

  /**
   * Create a new {@link DD64bTraceId 64-bit TraceId} from the given {@code long} interpreted as the
   * bits of the unsigned 64-bit id. This means that values larger than {@link Long#MAX_VALUE} will
   * be represented as negative numbers.
   *
   * @param id The {@code long} representing the bits of the unsigned 64-bit id.
   * @return A new {@link DDTraceId} instance.
   */
  static DDTraceId from(long id) {
    return DD64bTraceId.from(id);
  }

  /**
   * Create a new {@link DD64bTraceId 64-bit TraceId} from the given {@link #toString() String
   * representation}.
   *
   * @param s The {@link #toString() String representation} of a {@link DDTraceId}.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given {@link String} does not represent a valid number.
   */
  static DDTraceId from(String s) throws NumberFormatException {
    return DD64bTraceId.create(DDId.parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@link DDTraceId} from the given {@link #toHexString() hexadecimal
   * representation}.
   *
   * @param s The hexadecimal {@link String} representation of a {@link DD128bTraceId} to parse.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given {@link #toHexString() hexadecimal String} does not
   *     represent a valid number.
   */
  static DDTraceId fromHex(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s cannot be null");
    }
    return s.length() > 16 ? DD128bTraceId.fromHex(s) : DD64bTraceId.fromHex(s);
  }

  /**
   * Returns a 64-bit only decimal {@link String} representation of the {@link DDTraceId}. The
   * {@link String} will be cached.
   *
   * @return A cached 64-bit only decimal {@link String} representation of the {@link DDTraceId}
   *     instance.
   */
  @Override
  String toString();

  /**
   * Returns the lower-case non-zero-padded hexadecimal {@link String} representation of the {@link
   * DDTraceId}. This hexadecimal {@code String} <strong>will not be cached</strong>.
   *
   * @return A lower-case non-zero-padded hexadecimal {@link String} representation of the {@link
   *     DDTraceId} instance.
   */
  String toHexString();

  /**
   * Returns the lower-case zero-padded {@link #toHexString() hexadecimal String} representation of
   * the {@link DDTraceId}. The size will be rounded up to <code>16</code> or <code>32</code>
   * characters. This hexadecimal {@code String} <strong>will not be cached</strong>.
   *
   * @param size The size in characters of the zero-padded {@link String} (rounded up to <code>16
   *     </code> or <code>32</code>).
   * @return A lower-case zero-padded {@link #toHexString() String representation} representation of
   *     the {@link DDTraceId} instance.
   */
  String toHexStringPadded(int size);

  /**
   * Returns the id as a long representing the bits of the unsigned 64 bit id. This means that
   * values larger than {@link Long#MAX_VALUE} will be represented as negative numbers.
   *
   * @return long value representing the bits of the unsigned 64 bit id.
   */
  long toLong(); // TODO Cleanup name and Javadoc?

  /**
   * Get the high-order 64 bits of 128-bit TraceId.
   *
   * @return The high-order 64 bits of the 128-bit trace id, <code>0</code> on 64-bit TraceId.
   */
  long getHighOrderBits();
}
