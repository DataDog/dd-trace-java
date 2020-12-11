package datadog.trace.core.histogram;

import java.nio.ByteBuffer;

public interface Histogram {

  void accept(long value);

  double valueAtQuantile(double quantile);

  double max();

  void clear();

  ByteBuffer serialize();
}
