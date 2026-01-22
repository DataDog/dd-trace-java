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
}
