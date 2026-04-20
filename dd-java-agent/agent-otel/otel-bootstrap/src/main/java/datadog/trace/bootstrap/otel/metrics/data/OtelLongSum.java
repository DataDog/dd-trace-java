package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;
import java.util.concurrent.atomic.LongAdder;

final class OtelLongSum extends OtelAggregator {
  private final LongAdder total = new LongAdder();

  @Override
  void doRecordLong(long value) {
    total.add(value);
  }

  @Override
  OtlpDataPoint doCollect(boolean reset) {
    return new OtlpLongPoint(reset ? total.sumThenReset() : total.sum());
  }
}
