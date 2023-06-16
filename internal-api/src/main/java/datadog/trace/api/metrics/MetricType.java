package datadog.trace.api.metrics;

/**
 * This enumerates defines the metrics types and their matching telemetry name. @See <a
 * href="https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/producing-telemetry.md#generate-metrics">the
 * telemetry documentation</a>g
 */
public enum MetricType {
  COUNTER("count"),
  GAUGE("gauge"),
  METER("rate");

  private final String telemetryName;

  MetricType(String telemetryName) {
    this.telemetryName = telemetryName;
  }

  public String telemetryName() {
    return this.telemetryName;
  }
}
