package datadog.trace.api.config;

public final class OtelConfig {

  public static final String METRICS_OTEL_ENABLED = "metrics.otel.enabled";
  public static final String OTEL_RESOURCE_ATTRIBUTES = "otel.resource.attributes";
  public static final String OTEL_METRICS_EXPORTER = "otel.metrics.exporter";
  public static final String OTEL_METRIC_EXPORT_INTERVAL = "otel.metric.export.interval";
  public static final String OTEL_METRIC_EXPORT_TIMEOUT = "otel.metric.export.timeout";
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

  public static final String OTEL_METRIC_ENDPOINT_SUFFIX = "v1/metrics";
  public static final String OTEL_METRIC_ENDPOINT_HTTP_PORT = "4318";
  public static final String OTEL_METRIC_ENDPOINT_GRPC_PORT = "4317";

  public enum Temporality {
    CUMULATIVE,
    DELTA,
    LOWMEMORY;
  }

  public enum Exporter {
    OTLP,
    NONE;
  }

  public enum Protocol {
    GRPC,
    HTTP_PROTOBUF,
    HTTP_JSON;
  }

  private OtelConfig() {}
}
