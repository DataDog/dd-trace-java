package datadog.trace.api.openfeature.config.ufc.v1;

import java.util.List;

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
