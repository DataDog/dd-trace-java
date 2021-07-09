package datadog.trace.common.metrics;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsAggregatorFactory {
  private static final Logger log = LoggerFactory.getLogger(MetricsAggregatorFactory.class);

  public static MetricsAggregator createMetricsAggregator(
      Config config, DDAgentFeaturesDiscovery discovery) {
    if (config.isTracerMetricsEnabled()) {
      log.debug("tracer metrics enabled");
      return new ConflatingMetricsAggregator(config, discovery);
    }
    log.debug("tracer metrics disabled");
    return NoOpMetricsAggregator.INSTANCE;
  }
}
