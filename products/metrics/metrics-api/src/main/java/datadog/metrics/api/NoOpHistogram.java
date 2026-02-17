package datadog.metrics.api;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

class NoOpHistogram implements Histogram {
  public static final Histogram INSTANCE = new NoOpHistogram();

  @Override
  public double getCount() {
    return 0;
  }

  @Override
  public boolean isEmpty() {
    return true;
  }

  @Override
  public void accept(double value) {}

  @Override
  public void accept(double value, double count) {}

  @Override
  public double getValueAtQuantile(double quantile) {
    return 0;
  }

  @Override
  public double getMinValue() {
    return 0;
  }

  @Override
  public double getMaxValue() {
    return 0;
  }

  @Override
  public List<Double> getBinBoundaries() {
    return Collections.emptyList();
  }

  @Override
  public List<Double> getBinCounts() {
    return Collections.emptyList();
  }

  @Override
  public void clear() {}

  @Override
  public ByteBuffer serialize() {
    return null;
  }
}
