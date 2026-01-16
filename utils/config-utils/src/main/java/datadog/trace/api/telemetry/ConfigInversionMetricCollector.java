package datadog.trace.api.telemetry;

public interface ConfigInversionMetricCollector {
  void setUndocumentedEnvVarMetric(String configName);
}
