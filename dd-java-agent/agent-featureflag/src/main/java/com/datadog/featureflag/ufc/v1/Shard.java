package com.datadog.featureflag.ufc.v1;

import java.util.List;

public class Shard {
  public final String salt;
  public final List<ShardRange> ranges;
  public final int totalShards;

  public Shard(String salt, List<ShardRange> ranges, int totalShards) {
    this.salt = salt;
    this.ranges = ranges;
    this.totalShards = totalShards;
  }
}
