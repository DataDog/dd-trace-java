package datadog.trace.api;

import datadog.trace.api.internal.util.LongStringUtils;

/**
 * Class encapsulating the id used for TraceIds.
 *
 * <p>It contains parsing and formatting to string for both decimal and hexadecimal representations.
 * The string representations are either kept from parsing, or generated on demand and cached.
 */
public abstract class DDTraceId {
  /**
   * Invalid TraceId value used to denote no TraceId.
   *
   * <p>This is an instance of a private {@link DDTraceId} subtype (a sibling of {@link
   * DD64bTraceId}), not a {@code DD64bTraceId}, and that is deliberate. {@code DD64bTraceId} is a
   * subclass of {@code DDTraceId}, so the JVM must initialize {@code DDTraceId} before {@code
   * DD64bTraceId}. If this constant were built via {@code DD64bTraceId.from(0)} (as it once was),
   * {@code DDTraceId.<clinit>} would initialize {@code DD64bTraceId} while holding the {@code
   * DDTraceId} init lock, and two threads first touching the classes from opposite ends would
   * deadlock on the two class-initialization locks. Building it from a sibling type keeps {@code
   * DDTraceId.<clinit>} free of any reference to the subclass.
   *
   * <p>To test whether an id is zero, prefer {@link #isZero()} over {@code == DDTraceId.ZERO}: a
   * zero id parsed via the 64-bit factories is a distinct instance, not this singleton.
   */
  public static final DDTraceId ZERO = new ConstantId(0, "0");

  /** Convenience constant used from tests. See {@link #ZERO} for why this is a sibling type. */
  public static final DDTraceId ONE = new ConstantId(1, "1");

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
   * Returns whether this {@link DDTraceId} is zero, the value used to denote no/invalid TraceId.
   *
   * <p>Prefer this over {@code == DDTraceId.ZERO}: it is value-based, so it recognizes a zero id
   * regardless of its concrete type or how it was created (e.g. a zero parsed via the 64-bit
   * factories, which is a distinct instance from the {@link #ZERO} singleton).
   *
   * @return {@code true} if both the high- and low-order 64 bits are zero.
   */
  public boolean isZero() {
    return toHighOrderLong() == 0 && toLong() == 0;
  }

  /**
   * Minimal concrete {@link DDTraceId} backing the {@link #ZERO} and {@link #ONE} constants. It is
   * a sibling of {@link DD64bTraceId} (it extends {@link DDTraceId} directly), so constructing
   * these constants in {@code DDTraceId.<clinit>} never initializes {@code DD64bTraceId} (which
   * would create a class-initialization deadlock; see {@link #ZERO}). It represents a 64-bit id and
   * formats identically to the equivalent {@link DD64bTraceId}.
   */
  private static final class ConstantId extends DDTraceId {
    private final long id;
    private final String str;
    private String hexStr; // cache for hex string representation

    private ConstantId(long id, String str) {
      this.id = id;
      this.str = str;
    }

    @Override
    public String toString() {
      return this.str;
    }

    @Override
    public String toHexString() {
      String hexStr = this.hexStr;
      // This race condition is intentional and benign.
      // The worst that can happen is that an identical value is produced and written into the
      // field.
      if (hexStr == null) {
        this.hexStr = hexStr = LongStringUtils.toHexStringPadded(this.id, 32);
      }
      return hexStr;
    }

    @Override
    public String toHexStringPadded(int size) {
      if (size > 16) {
        return toHexString();
      }
      return LongStringUtils.toHexStringPadded(this.id, size);
    }

    @Override
    public long toLong() {
      return this.id;
    }

    @Override
    public long toHighOrderLong() {
      return 0;
    }
  }
}
