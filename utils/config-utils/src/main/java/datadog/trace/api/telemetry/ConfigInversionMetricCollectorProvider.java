package datadog.trace.api.telemetry;

public final class ConfigInversionMetricCollectorProvider {
  private static ConfigInversionMetricCollector INSTANCE = null;

  private ConfigInversionMetricCollectorProvider() {}

  public static ConfigInversionMetricCollector get() {
    if (INSTANCE == null) {
      throw new IllegalStateException(
          "ConfigInversionMetricCollectorService has not been registered.");
    }
    return INSTANCE;
  }

  public static void register(ConfigInversionMetricCollector instance) {
    INSTANCE = instance;
  }
}
