package datadog.trace.common.metrics;

import datadog.trace.core.CoreSpan;
import java.util.List;

public interface MetricsAggregator extends AutoCloseable {
  void start();

  void report();

  boolean publish(List<? extends CoreSpan<?>> trace);
}
