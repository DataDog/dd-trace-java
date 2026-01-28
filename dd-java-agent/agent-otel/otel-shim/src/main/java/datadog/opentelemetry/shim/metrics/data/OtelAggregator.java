package datadog.opentelemetry.shim.metrics.data;

public abstract class OtelAggregator {
  private volatile boolean empty = true;

  public final boolean isEmpty() {
    return empty;
  }

  public final void recordDouble(double value) {
    doRecordDouble(value);
    empty = false;
  }

  public final void recordLong(long value) {
    doRecordLong(value);
    empty = false;
  }

  public final OtelPoint collect() {
    return doCollect(false);
  }

  public final OtelPoint collectAndReset() {
    OtelPoint point = doCollect(true);
    empty = true;
    return point;
  }

  protected void doRecordDouble(double value) {
    throw new UnsupportedOperationException();
  }

  protected void doRecordLong(long value) {
    throw new UnsupportedOperationException();
  }

  protected abstract OtelPoint doCollect(boolean reset);
}
