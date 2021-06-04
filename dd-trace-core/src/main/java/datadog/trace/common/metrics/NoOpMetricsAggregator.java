package datadog.trace.common.metrics;

import datadog.trace.core.CoreSpan;
import java.util.List;

public final class NoOpMetricsAggregator implements MetricsAggregator {

  static final NoOpMetricsAggregator INSTANCE = new NoOpMetricsAggregator();

  @Override
  public void start() {}

  @Override
  public boolean report() {
    return false;
  }

  @Override
  public boolean publish(List<? extends CoreSpan<?>> trace) {
    return false;
  }

  @Override
  public void close() {}
}
