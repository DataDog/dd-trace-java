package datadog.trace.api;

/**
 * Class encapsulating the id used for TraceIds.
 *
 * <p>It contains parsing and formatting to string for both decimal and hexadecimal representations.
 * The string representations are either kept from parsing, or generated on demand and cached.
 */
public abstract class DDTraceId {
  // ZERO/ONE use DDTraceIdConstant (a sibling of DD64bTraceId) rather than DD64bTraceId so that
  // initializing DDTraceId never initializes its subclass, which would deadlock. Test for zero with
  // isValid(), not == ZERO.

  /** Invalid TraceId value used to denote no/unset TraceId. */
  public static final DDTraceId ZERO = new DDTraceIdConstant(0, "0");

  /** Convenience constant used from tests. */
  public static final DDTraceId ONE = new DDTraceIdConstant(1, "1");

  /**
   * Creates a new {@link DD64bTraceId 64-bit TraceId} from the given {@code long} interpreted as
   * the bits of the unsigned 64-bit id. This means that values larger than {@link Long#MAX_VALUE}
   * will be represented as negative numbers.
   *
   * @param id The {@code long} representing the bits of the unsigned 64-bit id.
   * @return A new {@link DDTraceId} instance.
   */
  public static DDTraceId from(long id) {
    // Zero maps to the ZERO constant so callers can still compare against it.
    return id == 0 ? ZERO : DD64bTraceId.from(id);
  }

  /**
   * Creates a new {@link DD64bTraceId 64-bit TraceId} from the given {@link #toString() String
   * representation}.
   *
   * @param s The {@link #toString() String representation} of a {@link DDTraceId}.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given {@link String} does not represent a valid number.
   */
  public static DDTraceId from(String s) throws NumberFormatException {
    DD64bTraceId id = DD64bTraceId.from(s);
    return id.toLong() == 0 ? ZERO : id;
  }

  /**
   * Creates a new {@link DDTraceId} from the given {@link #toHexString() hexadecimal
   * representation}.
   *
   * @param s The hexadecimal {@link String} representation of a {@link DD128bTraceId} to parse.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given {@link #toHexString() hexadecimal String} does not
   *     represent a valid number.
   */
  public static DDTraceId fromHex(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("s cannot be null");
    }
    if (s.length() > 16) {
      return DD128bTraceId.fromHex(s);
    }
    DD64bTraceId id = DD64bTraceId.fromHex(s);
    return id.toLong() == 0 ? ZERO : id;
  }

  /**
   * Returns a 64-bit only decimal {@link String} representation of the {@link DDTraceId}. The
   * {@link String} will be cached.
   *
   * @return A cached 64-bit only decimal {@link String} representation of the {@link DDTraceId}
   *     instance.
   */
  @Override
  public abstract String toString();

  /**
   * Returns the lower-case zero-padded 32 hexadecimal characters {@link String} representation of
   * the {@link DDTraceId}. The * {@link String} will be cached.
   *
   * @return A cached lower-case zero-padded 32 hexadecimal characters {@link String} representation
   *     of the {@link DDTraceId} instance.
   */
  public abstract String toHexString();

  /**
   * Returns the lower-case zero-padded {@link #toHexString() hexadecimal String} representation of
   * the {@link DDTraceId}. The size will be rounded up to <code>16</code> or <code>32</code>
   * characters. This hexadecimal {@link String} <strong>will not be cached</strong>.
   *
   * @param size The size in characters of the zero-padded {@link String} (rounded up to <code>16
   *     </code> or <code>32</code>).
   * @return A lower-case zero-padded {@link #toHexString() String representation} representation of
   *     the {@link DDTraceId} instance.
   */
  public abstract String toHexStringPadded(int size);

  /**
   * Returns the low-order 64 bits of the {@link DDTraceId} as an unsigned {@code long}. This means
   * that values larger than {@link Long#MAX_VALUE} will be represented as negative numbers.
   *
   * @return The low-order 64 bits of the {@link DDTraceId} as an unsigned {@code long}.
   */
  public abstract long toLong();

  /**
   * Returns the high-order 64 bits of 128-bit {@link DDTraceId} as un unsigned {@code long}. This
   * means that values larger than {@link Long#MAX_VALUE} will be represented as negative numbers.
   *
   * @return The high-order 64 bits of the 128-bit {@link DDTraceId} as an unsigned {@code long},
   *     <code>0</code> for 64-bit {@link DDTraceId} only.
   */
  public abstract long toHighOrderLong();

  /**
   * Returns whether this is a valid (non-zero) {@link DDTraceId}; zero denotes no/unset TraceId.
   * Value-based, so it recognizes a zero id of any concrete type.
   *
   * @return {@code true} if any bit is non-zero.
   */
  public boolean isValid() {
    return toHighOrderLong() != 0 || toLong() != 0;
  }
}
