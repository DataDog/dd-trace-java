package datadog.trace.core.histogram;

public class StubHistogram implements Histogram, HistogramFactory {
  private static final byte[] EMPTY = new byte[0];

  @Override
  public void accept(long value) {}

  @Override
  public void clear() {}

  @Override
  public byte[] serialize() {
    return EMPTY;
  }

  @Override
  public Histogram newHistogram() {
    return new StubHistogram();
  }
}
