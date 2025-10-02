package datadog.trace.api.telemetry;

public interface OtelEnvMetricCollector {
  void setHidingOtelEnvVarMetric(String otelName, String ddName);

  void setInvalidOtelEnvVarMetric(String otelName, String ddName);

  void setUnsupportedOtelEnvVarMetric(String otelName);
}
