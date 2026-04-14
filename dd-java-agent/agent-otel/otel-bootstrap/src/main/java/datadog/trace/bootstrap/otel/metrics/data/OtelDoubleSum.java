package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import java.util.concurrent.atomic.DoubleAdder;

final class OtelDoubleSum extends OtelAggregator {
  private final DoubleAdder total = new DoubleAdder();

  @Override
  void doRecordDouble(double value) {
    total.add(value);
  }

  @Override
  OtlpDataPoint doCollect(boolean reset) {
    return new OtlpDoublePoint(reset ? total.sumThenReset() : total.sum());
  }
}
