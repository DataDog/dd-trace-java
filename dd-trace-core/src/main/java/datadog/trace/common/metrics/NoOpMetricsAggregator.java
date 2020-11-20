package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanData;
import java.util.List;

public final class NoOpMetricsAggregator implements MetricsAggregator {

  static final NoOpMetricsAggregator INSTANCE = new NoOpMetricsAggregator();

  @Override
  public void start() {}

  @Override
  public void publish(List<? extends AgentSpanData> trace) {}

  @Override
  public void close() {}
}
