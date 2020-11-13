package datadog.trace.common.metrics;

import datadog.trace.core.DDSpanData;
import java.util.List;

public interface MetricsAggregator extends AutoCloseable {
  void start();

  void publish(List<? extends DDSpanData> trace);
}
