package datadog.trace.api.telemetry;

public final class OtelEnvMetricCollectorProvider {
  private static OtelEnvMetricCollector INSTANCE = null;

  private OtelEnvMetricCollectorProvider() {}

  public static OtelEnvMetricCollector get() {
    if (INSTANCE == null) {
      throw new IllegalStateException("OtelEnvMetricCollectorService has not been registered.");
    }
    return INSTANCE;
  }

  public static void register(OtelEnvMetricCollector instance) {
    INSTANCE = instance;
  }
}
