package datadog.trace.api.featureflag.ufc.v1;

import java.util.List;

public class Shard {
  public final String salt;
  public final List<ShardRange> ranges;
  public final long totalShards;

  public Shard(final String salt, final List<ShardRange> ranges, final long totalShards) {
    this.salt = salt;
    this.ranges = ranges;
    this.totalShards = totalShards;
  }
}
