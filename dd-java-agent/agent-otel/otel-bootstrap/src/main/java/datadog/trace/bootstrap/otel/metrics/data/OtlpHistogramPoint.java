package datadog.trace.bootstrap.otel.metrics.data;

import java.util.List;

public final class OtlpHistogramPoint extends OtlpDataPoint {
  public final double count;
  public final List<Double> bucketBoundaries;
  public final List<Double> bucketCounts;
  public final double sum;
  public final double min;
  public final double max;

  OtlpHistogramPoint(
      double count,
      List<Double> bucketBoundaries,
      List<Double> bucketCounts,
      double sum,
      double min,
      double max) {
    this.count = count;
    this.bucketBoundaries = bucketBoundaries;
    this.bucketCounts = bucketCounts;
    this.sum = sum;
    this.min = min;
    this.max = max;
  }
}
