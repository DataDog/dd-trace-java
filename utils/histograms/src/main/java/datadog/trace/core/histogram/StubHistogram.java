package datadog.trace.core.histogram;

import java.nio.ByteBuffer;

public class StubHistogram implements Histogram, HistogramFactory {
  private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

  @Override
  public void accept(long value) {}

  @Override
  public void clear() {}

  @Override
  public ByteBuffer serialize() {
    return EMPTY;
  }

  @Override
  public Histogram newHistogram() {
    return new StubHistogram();
  }
}
