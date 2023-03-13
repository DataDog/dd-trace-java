package datadog.trace.api;

/**
 * Class encapsulating the unsigned 128-bit id used for TraceIds.
 *
 * <p>It contains parsing and formatting to string for both decimal and hexadecimal representations.
 * The string representations are either kept from parsing, or generated on demand and cached.
 *
 * <p>{@link DD128bTraceId} can represent either a 128-bit TraceId or a 64-bit TraceId. For 128-bit
 * TraceId, {@link #mostSigBits} contains a 32-bit timestamp store on the 32 higher bits and {@link
 * #leastSigBits} contains a unique and random 64-bit id. For 64-bit TraceId, {@link #mostSigBits}
 * is set to <code>0</code> and {@link #leastSigBits} contains a unique and random 63-bit id.
 */
public class DD128bTraceId implements DDTraceId {
  public static final DD128bTraceId ZERO =
      new DD128bTraceId(0, 0, "00000000000000000000000000000000");

  /** Represents the high-order 64 bits of the 128-bit trace id. */
  private final long mostSigBits;
  /** Represents the low-order 64 bits of the 128-bit trace id. */
  private final long leastSigBits;

  private String str; // cache for string representation

  private DD128bTraceId(long mostSigBits, long leastSigBits, String str) {
    this.mostSigBits = mostSigBits;
    this.leastSigBits = leastSigBits;
    this.str = str;
  }

  /**
   * Create a new 128-bit {@link DD128bTraceId} from the given {@code long}s interpreted as high
   * order and low order bits of the 128-bit id.
   *
   * @param mostSigBits A {@code long} representing the high-order bits of the {@link
   *     DD128bTraceId}.
   * @param leastSigBits A {@code long} representing the random id low-order bits.
   * @return The created TraceId instance.
   */
  public static DD128bTraceId from(long mostSigBits, long leastSigBits) {
    return new DD128bTraceId(mostSigBits, leastSigBits, null);
  }

  @Override
  public String toHexString() {
    return null;
  }

  @Override
  public String toHexStringPadded(int size) {
    return null;
  }

  @Override
  public String toHexStringOrOriginal() {
    return null;
  }

  @Override
  public String toHexStringPaddedOrOriginal(int size) {
    return null;
  }

  @Override
  public long toLong() {
    return 0;
  }
}
