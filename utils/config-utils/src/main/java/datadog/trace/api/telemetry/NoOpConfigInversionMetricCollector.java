package datadog.trace.api.telemetry;

/**
 * NOOP implementation of ConfigInversionMetricCollector. Used as a default when the real collector
 * is not registered during build tasks like instrumentJava.
 */
public final class NoOpConfigInversionMetricCollector implements ConfigInversionMetricCollector {
  private static final NoOpConfigInversionMetricCollector INSTANCE =
      new NoOpConfigInversionMetricCollector();

  private NoOpConfigInversionMetricCollector() {}

  public static NoOpConfigInversionMetricCollector getInstance() {
    return INSTANCE;
  }

  @Override
  public void setUndocumentedEnvVarMetric(String configName) {
    // NOOP - do nothing
  }
}
