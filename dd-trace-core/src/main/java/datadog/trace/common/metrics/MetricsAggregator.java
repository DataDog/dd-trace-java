package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanData;
import java.util.List;

public interface MetricsAggregator extends AutoCloseable {
  void start();

  void publish(List<? extends AgentSpanData> trace);
}
