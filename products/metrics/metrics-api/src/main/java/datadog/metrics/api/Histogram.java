package datadog.metrics.api;

import java.nio.ByteBuffer;

public interface Histogram {

  double getCount();

  boolean isEmpty();

  void accept(double value);

  void accept(double value, double count);

  double getValueAtQuantile(double quantile);

  double getMinValue();

  double getMaxValue();

  void clear();

  ByteBuffer serialize();

  static Histogram newHistogram() {
    return Histograms.factory.newHistogram();
  }

  static Histogram newLogHistogram() {
    return Histograms.factory.newLogHistogram();
  }

  static Histogram newHistogram(double relativeAccuracy, int maxNumBins) {
    return Histograms.factory.newHistogram(relativeAccuracy, maxNumBins);
  }
}
