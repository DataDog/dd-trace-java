package datadog.trace.common.metrics;

import datadog.metrics.api.Histogram;
import datadog.metrics.api.HistogramWithSum;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Projects a client-side-stats {@link Histogram} (a DDSketch over span durations recorded in
 * <em>nanoseconds</em>) onto the fixed explicit-bounds histogram layout mandated by the OTLP Trace
 * Metrics Export RFC.
 */
final class OtlpHistogramBuckets {
  private OtlpHistogramBuckets() {}

  private static final double NANOS_PER_SECOND = 1_000_000_000d;

  static final double[] BOUNDS_SECONDS = {
    0.002, 0.004, 0.006, 0.008, 0.01, 0.05, 0.1, 0.2, 0.4, 0.8, 1, 1.4, 2, 5, 10, 15
  };

  static final List<Double> EXPLICIT_BOUNDS;

  static {
    List<Double> bounds = new ArrayList<>(BOUNDS_SECONDS.length + 1);
    for (double bound : BOUNDS_SECONDS) {
      bounds.add(bound);
    }
    bounds.add(Double.POSITIVE_INFINITY);
    EXPLICIT_BOUNDS = Collections.unmodifiableList(bounds);
  }

  static int bucketIndex(double seconds) {
    for (int i = 0; i < BOUNDS_SECONDS.length; i++) {
      if (seconds <= BOUNDS_SECONDS[i]) {
        return i;
      }
    }
    return BOUNDS_SECONDS.length; // overflow
  }

  /**
   * Re-bins {@code histogram} (nanosecond-valued) into an {@link OtlpHistogramPoint} expressed in
   * seconds with OTLP's fixed bucket layout. {@code count}, {@code min}, and {@code max} are taken
   * directly from the sketch; {@code sum} is exact when the sketch tracks it ({@link
   * HistogramWithSum}) and otherwise best-effort estimated from bin upper bounds.
   */
  static OtlpHistogramPoint toHistogramPoint(Histogram histogram) {
    long[] bucketCounts = new long[BOUNDS_SECONDS.length + 1];

    List<Double> binBoundaries = histogram.getBinBoundaries();
    List<Double> binCounts = histogram.getBinCounts();
    double estimatedSumSeconds = 0d;
    for (int i = 0; i < binBoundaries.size(); i++) {
      double upperSeconds = binBoundaries.get(i) / NANOS_PER_SECOND;
      long count = (long) binCounts.get(i).doubleValue();
      bucketCounts[bucketIndex(upperSeconds)] += count;
      estimatedSumSeconds += upperSeconds * count;
    }

    List<Double> counts = new ArrayList<>(bucketCounts.length);
    for (long count : bucketCounts) {
      counts.add((double) count);
    }

    double sumSeconds =
        histogram instanceof HistogramWithSum
            ? ((HistogramWithSum) histogram).getSum() / NANOS_PER_SECOND
            : estimatedSumSeconds;

    double minSeconds = histogram.isEmpty() ? 0d : histogram.getMinValue() / NANOS_PER_SECOND;
    double maxSeconds = histogram.isEmpty() ? 0d : histogram.getMaxValue() / NANOS_PER_SECOND;

    return new OtlpHistogramPoint(
        histogram.getCount(), EXPLICIT_BOUNDS, counts, sumSeconds, minSeconds, maxSeconds);
  }
}
