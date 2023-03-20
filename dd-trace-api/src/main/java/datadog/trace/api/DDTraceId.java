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
   * Create a new {@code DDTrace64Id} from the given {@code long} interpreted as the bits of the
   * unsigned 64-bit id. This means that values larger than {@link Long#MAX_VALUE} will be
   * represented as negative numbers. Use {@link DD64bTraceId#from(long)} instead.
   *
   * @param id The {@code long} representing the bits of the unsigned 64-bit id.
   * @return A new {@link DDTraceId} instance.
   */
  @Deprecated
  static DDTraceId from(long id) {
    return DD64bTraceId.from(id);
  }

  /**
   * Create a new {@link DDTraceId} from the given {@link String} representation.
   *
   * @param s The {@link String} representation of a {@link DDTraceId}.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given {@link String} is not a valid number.
   */
  static DDTraceId from(String s)
      throws NumberFormatException { // TODO Update Javadoc to document 64-bit only (in mirror to
    // #toString)
    return DD64bTraceId.create(DDId.parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@link DDTraceId} from the given {@link String} hexadecimal representation.
   *
   * @param s The hexadecimal {@link String} representation of a {@link DD128bTraceId} to parse.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given hexadecimal {@link String} does not represent a
   *     valid number.
   */
  static DDTraceId fromHex(String s) throws NumberFormatException {
    return DD64bTraceId.create(
        DDId.parseUnsignedLongHex(s), null); // TODO Support 128b by testing String length
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
   * Returns the lower-case non zero-padded hexadecimal {@link String} representation of the {@link
   * DDTraceId}. This hexadecimal {@code String} <strong>will not be cached</strong>.
   *
   * @return A lower-case non zero-padded hexadecimal {@link String} representation of the {@link
   *     DDTraceId} instance.
   */
  String toHexString();

  /**
   * Returns the lower-case zero-padded hexadecimal {@link String} representation of the {@link
   * DDTraceId}. The size will be rounded up to <code>16</code> or <code>32</code> characters. This
   * hexadecimal {@code String} <strong>will not be cached</strong>.
   *
   * @param size The size in characters of the zero-padded {@link String} (rounded up to <code>16
   *     </code> or <code>32</code>).
   * @return A lower-case zero-padded hexadecimal {@link String} representation of the {@link
   *     DDTraceId} instance.
   */
  String toHexStringPadded(int size);

  /**
   * Returns the id as a long representing the bits of the unsigned 64 bit id. This means that
   * values larger than Long.MAX_VALUE will be represented as negative numbers. Use {@link
   * DD64bTraceId#toLong()} instead.
   *
   * @return long value representing the bits of the unsigned 64 bit id.
   */
  @Deprecated
  long toLong(); // TODO Cleanup?
}
