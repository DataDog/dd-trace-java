package datadog.trace.api.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NOOP implementation of ConfigInversionMetricCollector. Used as a default when the real collector
 * is not registered during build tasks like instrumentJava.
 */
public final class NoOpConfigInversionMetricCollector implements ConfigInversionMetricCollector {
  private static final NoOpConfigInversionMetricCollector INSTANCE =
      new NoOpConfigInversionMetricCollector();

  private static final Logger log =
      LoggerFactory.getLogger(NoOpConfigInversionMetricCollector.class);

  private NoOpConfigInversionMetricCollector() {}

  public static NoOpConfigInversionMetricCollector getInstance() {
    return INSTANCE;
  }

  @Override
  public void setUndocumentedEnvVarMetric(String configName) {
    log.debug("Environment variable {} is undocumented", configName);
  }
}
