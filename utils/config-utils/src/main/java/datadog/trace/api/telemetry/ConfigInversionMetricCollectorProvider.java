package datadog.trace.api.telemetry;

public final class ConfigInversionMetricCollectorProvider {
  private static ConfigInversionMetricCollector INSTANCE =
      NoOpConfigInversionMetricCollector.getInstance();

  private ConfigInversionMetricCollectorProvider() {}

  public static ConfigInversionMetricCollector get() {
    return INSTANCE;
  }

  public static void register(ConfigInversionMetricCollector instance) {
    INSTANCE = instance;
  }
}
