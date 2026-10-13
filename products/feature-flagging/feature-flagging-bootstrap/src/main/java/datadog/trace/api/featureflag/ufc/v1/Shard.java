package datadog.trace.api.featureflag.ufc.v1;

import java.util.List;

/**
 * A UFC shard.
 *
 * <p>{@code totalShards} stores the unsigned 32-bit wire value in an {@code int} to preserve this
 * bootstrap model's binary compatibility. Consumers must use {@link Integer#toUnsignedLong(int)}
 * before comparing it or using it for arithmetic.
 */
public class Shard {
  public final String salt;
  public final List<ShardRange> ranges;
  public final int totalShards;

  public Shard(final String salt, final List<ShardRange> ranges, final int totalShards) {
    this.salt = salt;
    this.ranges = ranges;
    this.totalShards = totalShards;
  }
}
