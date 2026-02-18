package datadog.trace.api.config;

public final class OtlpConfig {

  public static final String METRICS_OTEL_ENABLED = "metrics.otel.enabled";
  public static final String METRICS_OTEL_INTERVAL = "metrics.otel.interval";
  public static final String METRICS_OTEL_TIMEOUT = "metrics.otel.timeout";
  public static final String METRICS_OTEL_CARDINALITY_LIMIT = "metrics.otel.cardinality.limit";

  public static final String OTLP_METRICS_ENDPOINT = "otlp.metrics.endpoint";
  public static final String OTLP_METRICS_HEADERS = "otlp.metrics.headers";
  public static final String OTLP_METRICS_PROTOCOL = "otlp.metrics.protocol";
  public static final String OTLP_METRICS_TIMEOUT = "otlp.metrics.timeout";
  public static final String OTLP_METRICS_TEMPORALITY_PREFERENCE =
      "otlp.metrics.temporality.preference";

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
