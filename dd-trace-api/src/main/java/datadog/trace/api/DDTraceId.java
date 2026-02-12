package datadog.trace.api;

import datadog.trace.api.internal.util.LongStringUtils;
import java.nio.ByteBuffer;

/**
 * Class encapsulating the id used for TraceIds.
 *
 * <p>It contains parsing and formatting to string for both decimal and hexadecimal representations.
 * The string representations are either kept from parsing, or generated on demand and cached.
 */
public abstract class DDTraceId {
  /** Invalid TraceId value used to denote no TraceId. */
  public static final DDTraceId ZERO = from(0);

  /** Convenience constant used from tests */
  public static final DDTraceId ONE = from(1);

  /**
   * Creates a new {@link DD64bTraceId 64-bit TraceId} from the given {@code long} interpreted as
   * the bits of the unsigned 64-bit id. This means that values larger than {@link Long#MAX_VALUE}
   * will be represented as negative numbers.
   *
   * @param id The {@code long} representing the bits of the unsigned 64-bit id.
   * @return A new {@link DDTraceId} instance.
   */
  public static DDTraceId from(long id) {
    return DD64bTraceId.from(id);
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
    return DD64bTraceId.create(LongStringUtils.parseUnsignedLong(s), s);
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
   * @return High-order and low-order bits as bytes array.
   */
  public byte[] to128BitBytes() {
    ByteBuffer buffer = ByteBuffer.allocate(16);
    buffer.putLong(toHighOrderLong());
    buffer.putLong(toLong());
    return buffer.array();
  }
}
