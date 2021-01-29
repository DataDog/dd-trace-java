package datadog.trace.common.metrics;

import datadog.trace.core.histogram.Histogram;
import datadog.trace.core.histogram.HistogramFactory;
import datadog.trace.core.histogram.Histograms;
import java.util.concurrent.atomic.AtomicLongArray;

/** Not thread-safe. Accumulates counts and durations. */
public final class AggregateMetric {

  static final long ERROR_TAG = 0x8000000000000000L;
  private static final HistogramFactory HISTOGRAM_FACTORY = Histograms.newHistogramFactory();

  private final Histogram okLatencies;
  private final Histogram errorLatencies;
  private int errorCount;
  private int hitCount;
  private long duration;

  public AggregateMetric() {
    okLatencies = HISTOGRAM_FACTORY.newHistogram();
    errorLatencies = HISTOGRAM_FACTORY.newHistogram();
  }

  public AggregateMetric recordDurations(int count, AtomicLongArray durations) {
    this.hitCount += count;
    for (int i = 0; i < count && i < durations.length(); ++i) {
      long duration = durations.getAndSet(i, 0);
      if ((duration & ERROR_TAG) == ERROR_TAG) {
        // then it's an error
        duration ^= ERROR_TAG;
        errorLatencies.accept(duration);
        ++this.errorCount;
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

  public long getDuration() {
    return duration;
  }

  public Histogram getOkLatencies() {
    return okLatencies;
  }

  public Histogram getErrorLatencies() {
    return errorLatencies;
  }

  public void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.duration = 0;
    this.okLatencies.clear();
    this.errorLatencies.clear();
  }
}
