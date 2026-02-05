package datadog.opentelemetry.shim.metrics.data;

import java.util.concurrent.atomic.DoubleAdder;

final class OtelDoubleSum extends OtelAggregator {
  private final DoubleAdder total = new DoubleAdder();

  @Override
  void doRecordDouble(double value) {
    total.add(value);
  }

  @Override
  OtelPoint doCollect(boolean reset) {
    return new OtelDoublePoint(reset ? total.sumThenReset() : total.sum());
  }
}
