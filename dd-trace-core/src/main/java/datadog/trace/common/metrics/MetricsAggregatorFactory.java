package datadog.trace.common.metrics;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsAggregatorFactory {
  private static final Logger log = LoggerFactory.getLogger(MetricsAggregatorFactory.class);

  public static MetricsAggregator createMetricsAggregator(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      HealthMetrics healthMetrics) {
    if (config.isTracerMetricsEnabled()) {
      log.debug("tracer metrics enabled");
      return new ConflatingMetricsAggregator(config, sharedCommunicationObjects, healthMetrics);
    }
    log.debug("tracer metrics disabled");
    return NoOpMetricsAggregator.INSTANCE;
  }
}
