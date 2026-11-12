package com.datadog.openfeature.internal.ufc;

/** A UFC shard range. {@code start} and {@code end} hold unsigned 32-bit wire values. */
public final class ShardRange {
  public final long start;
  public final long end;

  public ShardRange(final long start, final long end) {
    this.start = start;
    this.end = end;
  }
}
