package datadog.trace.common.metrics;

/** Not thread-safe. Accumulates counts and durations. */
public final class AggregateMetric {
  private int errorCount;
  private int hitCount;
  private long duration;

  public AggregateMetric addHits(int count) {
    hitCount += count;
    return this;
  }

  public AggregateMetric addErrors(int count) {
    errorCount += count;
    return this;
  }

  public AggregateMetric recordDurations(long errorMask, long... durations) {
    for (long d : durations) {
      duration += d;
    }
    return this;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public int getHitCount() {
    return hitCount;
  }

  public long getDuration() {
    return duration;
  }

  public void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.duration = 0;
  }
}
