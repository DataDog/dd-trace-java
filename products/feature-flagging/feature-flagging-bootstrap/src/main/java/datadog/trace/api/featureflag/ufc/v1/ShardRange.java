package datadog.trace.api.featureflag.ufc.v1;

public class ShardRange {
  public final int start;
  public final int end;

  public ShardRange(final int start, final int end) {
    this.start = start;
    this.end = end;
  }

  /**
   * Returns {@link #start} as the unsigned 32-bit value used in FFE configurations. The {@code
   * int}-backed field remains public for compatibility with existing UFC model consumers.
   */
  public long unsignedStart() {
    return Integer.toUnsignedLong(start);
  }

  /**
   * Returns {@link #end} as the unsigned 32-bit value used in FFE configurations. The {@code
   * int}-backed field remains public for compatibility with existing UFC model consumers.
   */
  public long unsignedEnd() {
    return Integer.toUnsignedLong(end);
  }
}
