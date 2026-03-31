package datadog.trace.api.config;

public final class OtlpConfig {

  public static final String METRICS_OTEL_ENABLED = "metrics.otel.enabled";
  public static final String METRICS_OTEL_INTERVAL = "metrics.otel.interval";
  public static final String METRICS_OTEL_TIMEOUT = "metrics.otel.timeout";
  public static final String METRICS_OTEL_CARDINALITY_LIMIT = "metrics.otel.cardinality.limit";

  public static final String OTLP_METRICS_ENDPOINT = "otlp.metrics.endpoint";
  public static final String OTLP_METRICS_HEADERS = "otlp.metrics.headers";
  public static final String OTLP_METRICS_PROTOCOL = "otlp.metrics.protocol";
  public static final String OTLP_METRICS_COMPRESSION = "otlp.metrics.compression";
  public static final String OTLP_METRICS_TIMEOUT = "otlp.metrics.timeout";
  public static final String OTLP_METRICS_TEMPORALITY_PREFERENCE =
      "otlp.metrics.temporality.preference";

  public static final String TRACE_OTEL_ENABLED = "trace.otel.enabled";
  public static final String TRACE_OTEL_EXPORTER = "trace.otel.exporter";

  public static final String OTLP_TRACES_ENDPOINT = "otlp.traces.endpoint";
  public static final String OTLP_TRACES_HEADERS = "otlp.traces.headers";
  public static final String OTLP_TRACES_PROTOCOL = "otlp.traces.protocol";
  public static final String OTLP_TRACES_COMPRESSION = "otlp.traces.compression";
  public static final String OTLP_TRACES_TIMEOUT = "otlp.traces.timeout";

  public enum Protocol {
    GRPC,
    HTTP_PROTOBUF,
    HTTP_JSON
  }

  public enum Compression {
    NONE,
    GZIP
  }

  public enum Temporality {
    CUMULATIVE,
    DELTA,
    LOWMEMORY
  }

  private OtlpConfig() {}
}
