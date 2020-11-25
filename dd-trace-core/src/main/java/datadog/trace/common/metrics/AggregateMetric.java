package datadog.trace.common.metrics;

import datadog.trace.core.histogram.Histogram;
import datadog.trace.core.histogram.HistogramFactory;
import datadog.trace.core.histogram.Histograms;

/** Not thread-safe. Accumulates counts and durations. */
public final class AggregateMetric {

  private static final HistogramFactory HISTOGRAM_FACTORY = Histograms.newHistogramFactory();

  private Histogram latencies;
  private Histogram errorLatencies;
  private int errorCount;
  private int hitCount;
  private long duration;

  public AggregateMetric() {
    latencies = HISTOGRAM_FACTORY.newHistogram();
    errorLatencies = HISTOGRAM_FACTORY.newHistogram();
  }

  public AggregateMetric addHits(int count) {
    hitCount += count;
    return this;
  }

  public AggregateMetric addErrors(int count) {
    errorCount += count;
    return this;
  }

  public AggregateMetric recordDurations(long errorMask, long... durations) {
    int i = 0;
    for (long duration : durations) {
      this.duration += duration;
      if (((errorMask >>> i) & 1) == 1) {
        errorLatencies.accept(duration);
      } else {
        latencies.accept(duration);
      }
      ++i;
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

  public byte[] getLatencies() {
    return latencies.serialize();
  }

  public byte[] getErrorLatencies() {
    return errorLatencies.serialize();
  }

  public void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.duration = 0;
    // TODO add ability to clear histogram in DDSketch
    this.latencies = HISTOGRAM_FACTORY.newHistogram();
    this.errorLatencies = HISTOGRAM_FACTORY.newHistogram();
  }
}
