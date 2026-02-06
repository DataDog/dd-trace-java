package datadog.opentelemetry.shim.metrics.data;

import java.util.List;

public final class OtelHistogramPoint extends OtelPoint {
  public final double count;
  public final List<Double> bucketBoundaries;
  public final List<Double> bucketCounts;
  public final double sum;

  OtelHistogramPoint(
      double count, List<Double> bucketBoundaries, List<Double> bucketCounts, double sum) {
    this.count = count;
    this.bucketBoundaries = bucketBoundaries;
    this.bucketCounts = bucketCounts;
    this.sum = sum;
  }
}
