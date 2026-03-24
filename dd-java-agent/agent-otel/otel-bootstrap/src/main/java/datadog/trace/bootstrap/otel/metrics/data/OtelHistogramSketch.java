package datadog.trace.bootstrap.otel.metrics.data;

import datadog.metrics.api.Histogram;
import datadog.metrics.api.HistogramWithSum;
import java.util.List;

final class OtelHistogramSketch extends OtelAggregator {
  private final HistogramWithSum histogram;

  OtelHistogramSketch(List<Double> bucketBoundaries) {
    this.histogram = Histogram.newHistogramWithSum(bucketBoundaries);
  }

  @Override
  void doRecordDouble(double value) {
    synchronized (histogram) {
      histogram.accept(value);
    }
  }

  @Override
  void doRecordLong(long value) {
    doRecordDouble(fixedPrecision(value));
  }

  @Override
  OtelPoint doCollect(boolean reset) {
    double count;
    List<Double> binBoundaries;
    List<Double> binCounts;
    double sum;
    synchronized (histogram) {
      count = histogram.getCount();
      binBoundaries = histogram.getBinBoundaries();
      binCounts = histogram.getBinCounts();
      sum = histogram.getSum();
      if (reset) {
        histogram.clear();
      }
    }
    return new OtelHistogramPoint(count, binBoundaries, binCounts, sum);
  }

  /** Truncate IEEE-754 floating-point value to 10 bits precision. */
  private static double fixedPrecision(long value) {
    long bits = Double.doubleToRawLongBits(value);
    // the mask include 1 bit sign 11 bits exponent (0xfff)
    // then we filter the mantissa to 10bits (0xff8) (9 bits as it has implicit value of 1)
    // 10 bits precision (any value will be +/- 1/1024)
    // https://en.wikipedia.org/wiki/Double-precision_floating-point_format
    bits &= 0xfffff80000000000L;
    return Double.longBitsToDouble(bits);
  }
}
