package datadog.trace.api.config;

public final class OtlpConfig {

  public static final String METRICS_OTEL_ENABLED = "metrics.otel.enabled";
  public static final String METRICS_OTEL_INTERVAL = "metrics.otel.interval";
  public static final String METRICS_OTEL_TIMEOUT = "metrics.otel.timeout";

  public static final String OTEL_EXPORTER_OTLP_ENDPOINT = "otel.exporter.otlp.endpoint";
  public static final String OTEL_EXPORTER_OTLP_HEADERS = "otel.exporter.otlp.headers";
  public static final String OTEL_EXPORTER_OTLP_PROTOCOL = "otel.exporter.otlp.protocol";
  public static final String OTEL_EXPORTER_OTLP_TIMEOUT = "otel.exporter.otlp.timeout";
  public static final String OTEL_EXPORTER_OTLP_METRICS_ENDPOINT =
      "otel.exporter.otlp.metrics.endpoint";
  public static final String OTEL_EXPORTER_OTLP_METRICS_HEADERS =
      "otel.exporter.otlp.metrics.headers";
  public static final String OTEL_EXPORTER_OTLP_METRICS_PROTOCOL =
      "otel.exporter.otlp.metrics.protocol";
  public static final String OTEL_EXPORTER_OTLP_METRICS_TIMEOUT =
      "otel.exporter.otlp.metrics.timeout";
  public static final String OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE =
      "otel.exporter.otlp.metrics.temporality.preference";

  public enum Protocol {
    GRPC,
    HTTP_PROTOBUF,
    HTTP_JSON
  }

  public enum Temporality {
    CUMULATIVE,
    DELTA,
    LOWMEMORY
  }

  private OtlpConfig() {}
}
