package datadog.trace.common.metrics;

import datadog.trace.core.histogram.Histogram;
import datadog.trace.core.histogram.Histograms;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicLongArray;

/** Not thread-safe. Accumulates counts and durations. */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
    justification = "Explicitly not thread-safe. Accumulates counts and durations.")
public final class AggregateMetric {

  static final long ERROR_TAG = 0x8000000000000000L;
  static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  private final Histogram okLatencies;
  private final Histogram errorLatencies;
  private int errorCount;
  private int hitCount;
  private int topLevelCount;
  private long duration;

  public AggregateMetric() {
    okLatencies = Histograms.newHistogram();
    errorLatencies = Histograms.newHistogram();
  }

  public AggregateMetric recordDurations(int count, AtomicLongArray durations) {
    this.hitCount += count;
    for (int i = 0; i < count && i < durations.length(); ++i) {
      long duration = durations.getAndSet(i, 0);
      if ((duration & TOP_LEVEL_TAG) == TOP_LEVEL_TAG) {
        duration ^= TOP_LEVEL_TAG;
        ++topLevelCount;
      }
      if ((duration & ERROR_TAG) == ERROR_TAG) {
        // then it's an error
        duration ^= ERROR_TAG;
        errorLatencies.accept(duration);
        ++errorCount;
      } else {
        okLatencies.accept(duration);
      }
      this.duration += duration;
    }
    return this;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public int getHitCount() {
    return hitCount;
  }

  public int getTopLevelCount() {
    return topLevelCount;
  }

  public long getDuration() {
    return duration;
  }

  public Histogram getOkLatencies() {
    return okLatencies;
  }

  public Histogram getErrorLatencies() {
    return errorLatencies;
  }

  @SuppressFBWarnings("AT_NONATOMIC_64BIT_PRIMITIVE")
  public void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.topLevelCount = 0;
    this.duration = 0;
    this.okLatencies.clear();
    this.errorLatencies.clear();
  }
}
