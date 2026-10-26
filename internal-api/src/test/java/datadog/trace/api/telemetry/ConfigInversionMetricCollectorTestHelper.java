package datadog.trace.api.telemetry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// Lightweight helper class to simulate Config Inversion ConfigHelper scenario where telemetry
// metrics are emitted for "unknown" environment variables.
public class ConfigInversionMetricCollectorTestHelper {
  private static final Set<String> SUPPORTED_ENV_VARS =
      new HashSet<>(Arrays.asList("DD_ENV", "DD_SERVICE"));

  private static final ConfigInversionMetricCollectorImpl configInversionMetricCollector =
      ConfigInversionMetricCollectorImpl.getInstance();

  public static void checkAndEmitUnsupported(String envVarName) {
    if (!SUPPORTED_ENV_VARS.contains(envVarName)) {
      configInversionMetricCollector.setUndocumentedEnvVarMetric(envVarName);
    }
  }
}
