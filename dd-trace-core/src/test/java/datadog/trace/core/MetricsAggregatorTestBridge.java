package datadog.trace.core;

import datadog.trace.common.metrics.MetricsAggregator;

/**
 * Bridge to expose package-private {@code CoreTracer.metricsAggregator} to tests in other packages.
 */
public class MetricsAggregatorTestBridge {
  public static MetricsAggregator getMetricsAggregator(CoreTracer tracer) {
    return tracer.metricsAggregator;
  }
}
