package datadog.trace.common.metrics;

import datadog.trace.api.Config;

public class MetricsAggregatorFactory {
  public static MetricsAggregator createMetricsAggregator(Config config) {
    if (config.isTracerMetricsEnabled()) {
      return new ConflatingMetricsAggregator(config);
    }
    return NoOpMetricsAggregator.INSTANCE;
  }
}
