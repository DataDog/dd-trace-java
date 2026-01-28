package datadog.opentelemetry.shim.metrics.data;

import java.util.concurrent.atomic.LongAdder;

final class OtelLongSum extends OtelAggregator {
  private final LongAdder total = new LongAdder();

  @Override
  protected void doRecordLong(long value) {
    total.add(value);
  }

  @Override
  protected OtelPoint doCollect(boolean reset) {
    return new OtelLongPoint(reset ? total.sumThenReset() : total.sum());
  }
}
