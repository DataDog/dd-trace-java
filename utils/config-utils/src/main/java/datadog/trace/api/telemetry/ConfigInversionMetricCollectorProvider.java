package datadog.trace.api.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigInversionMetricCollectorProvider {
  private static final Logger log =
      LoggerFactory.getLogger(ConfigInversionMetricCollectorProvider.class);
  private static ConfigInversionMetricCollector INSTANCE = null;

  private ConfigInversionMetricCollectorProvider() {}

  public static ConfigInversionMetricCollector get() {
    if (INSTANCE == null) {
      log.error(
          "ConfigInversionMetricCollector has not been registered. Defaulting to NoOp implementation.");
      // Return NoOp implementation for build tasks like instrumentJava that run before
      // implementation is registered
      return NoOpConfigInversionMetricCollector.getInstance();
    }
    return INSTANCE;
  }

  public static void register(ConfigInversionMetricCollector instance) {
    INSTANCE = instance;
  }
}
