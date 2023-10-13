package datadog.trace.common.metrics;

import datadog.trace.core.CoreSpan;
import java.util.List;
import java.util.concurrent.Future;

public interface MetricsAggregator extends AutoCloseable {
  void start();

  boolean report();

  Future<Boolean> forceReport();

  boolean publish(List<? extends CoreSpan<?>> trace);

  @Override
  void close();
}
