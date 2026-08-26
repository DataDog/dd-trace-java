package datadog.trace.api.featureflag.ufc.v1;

public class ShardRange {
  public final long start;
  public final long end;

  public ShardRange(final long start, final long end) {
    this.start = start;
    this.end = end;
  }
}
