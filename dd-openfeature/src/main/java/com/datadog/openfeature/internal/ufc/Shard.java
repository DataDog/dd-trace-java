package com.datadog.openfeature.internal.ufc;

import java.util.List;

/** A UFC shard. {@code totalShards} holds an unsigned 32-bit wire value. */
public final class Shard {
  public final String salt;
  public final List<ShardRange> ranges;
  public final long totalShards;

  public Shard(final String salt, final List<ShardRange> ranges, final long totalShards) {
    this.salt = salt;
    this.ranges = ranges;
    this.totalShards = totalShards;
  }
}
