package datadog.trace.api.featureflag.ufc.v1;

/**
 * A UFC shard range.
 *
 * <p>{@code start} and {@code end} store unsigned 32-bit wire values in {@code int} fields to
 * preserve this bootstrap model's binary compatibility. Consumers must use {@link
 * Integer#toUnsignedLong(int)} before comparing them.
 */
public class ShardRange {
  public final int start;
  public final int end;

  public ShardRange(final int start, final int end) {
    this.start = start;
    this.end = end;
  }
}
