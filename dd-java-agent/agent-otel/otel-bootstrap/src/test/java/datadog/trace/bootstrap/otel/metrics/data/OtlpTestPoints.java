package datadog.trace.bootstrap.otel.metrics.data;

import java.util.List;

/** Test-only factories giving tests access to the package-private data-point constructors. */
public final class OtlpTestPoints {
  private OtlpTestPoints() {}

  public static OtlpLongPoint longPoint(long value) {
    return new OtlpLongPoint(value);
  }

  public static OtlpDoublePoint doublePoint(double value) {
    return new OtlpDoublePoint(value);
  }

  public static OtlpHistogramPoint histogramPoint(
      double count,
      List<Double> bucketBoundaries,
      List<Double> bucketCounts,
      double sum,
      double min,
      double max) {
    return new OtlpHistogramPoint(count, bucketBoundaries, bucketCounts, sum, min, max);
  }
}
