package datadog.trace.common.metrics;

import datadog.trace.core.CoreSpan;
import java.util.List;

public final class NoOpMetricsAggregator implements MetricsAggregator {

  static final NoOpMetricsAggregator INSTANCE = new NoOpMetricsAggregator();

  @Override
  public void start() {}

  @Override
  public void report() {}

  @Override
  public void publish(List<? extends CoreSpan<?>> trace) {}

  @Override
  public void close() {}
}
