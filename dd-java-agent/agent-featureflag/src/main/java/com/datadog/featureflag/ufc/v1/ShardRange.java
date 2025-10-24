package com.datadog.featureflag.ufc.v1;

public class ShardRange {
  public final int start;
  public final int end;

  public ShardRange(int start, int end) {
    this.start = start;
    this.end = end;
  }
}
