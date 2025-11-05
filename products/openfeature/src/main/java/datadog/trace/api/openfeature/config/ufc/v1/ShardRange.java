package datadog.trace.api.openfeature.config.ufc.v1;

public class ShardRange {
  public final int start;
  public final int end;

  public ShardRange(final int start, final int end) {
    this.start = start;
    this.end = end;
  }
}
