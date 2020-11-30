package datadog.trace.common.metrics;

import datadog.trace.core.histogram.Histogram;
import datadog.trace.core.histogram.HistogramFactory;
import datadog.trace.core.histogram.Histograms;

/** Not thread-safe. Accumulates counts and durations. */
public final class AggregateMetric {

  private static final HistogramFactory HISTOGRAM_FACTORY = Histograms.newHistogramFactory();

  private Histogram hitLatencies;
  private Histogram errorLatencies;
  private int errorCount;
  private int hitCount;
  private long duration;

  public AggregateMetric() {
    hitLatencies = HISTOGRAM_FACTORY.newHistogram();
    errorLatencies = HISTOGRAM_FACTORY.newHistogram();
  }

  public AggregateMetric recordDurations(int count, long errorMask, long... durations) {
    this.hitCount += count;
    this.errorCount += Long.bitCount(errorMask);
    for (int i = 0; i < count && i < durations.length; ++i) {
      long duration = durations[i];
      this.duration += duration;
      if (((errorMask >>> i) & 1) == 1) {
        errorLatencies.accept(duration);
      } else {
        hitLatencies.accept(duration);
      }
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

  public byte[] getHitLatencies() {
    return hitLatencies.serialize();
  }

  public byte[] getErrorLatencies() {
    return errorLatencies.serialize();
  }

  public void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.duration = 0;
    this.hitLatencies.clear();
    this.errorLatencies.clear();
  }
}
