package datadog.trace.core.histogram;

import java.nio.ByteBuffer;

public interface Histogram {

  void accept(long value);

  void clear();

  ByteBuffer serialize();
}
