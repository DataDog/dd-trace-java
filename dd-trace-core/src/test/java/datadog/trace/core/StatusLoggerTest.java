package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class StatusLoggerTest extends DDCoreJavaSpecification {

  @Test
  void otlpExportDisabledByDefault() throws IOException {
    Map<String, Object> startupLog = startupLog(Config.get());

    assertEquals(false, startupLog.get("otlp_traces_export_enabled"));
    assertEquals(false, startupLog.get("otlp_metrics_export_enabled"));
    assertEquals(false, startupLog.get("otlp_logs_export_enabled"));
  }

  @Test
  @WithConfig(key = OtlpConfig.TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = OtlpConfig.METRICS_OTEL_ENABLED, value = "true")
  @WithConfig(key = OtlpConfig.METRICS_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = OtlpConfig.LOGS_OTEL_ENABLED, value = "true")
  @WithConfig(key = OtlpConfig.LOGS_OTEL_EXPORTER, value = "otlp")
  void otlpExportEnabledWhenConfigured() throws IOException {
    Map<String, Object> startupLog = startupLog(Config.get());

    assertEquals(true, startupLog.get("otlp_traces_export_enabled"));
    assertEquals(true, startupLog.get("otlp_metrics_export_enabled"));
    assertEquals(true, startupLog.get("otlp_logs_export_enabled"));
  }

  @Test
  @WithConfig(key = OtlpConfig.TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = OtlpConfig.METRICS_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = OtlpConfig.LOGS_OTEL_EXPORTER, value = "otlp")
  void metricsAndLogsRequireOtelSignalEnabled() throws IOException {
    // The OTLP exporter is selected for every signal, but the metrics and logs OTel signals are
    // left disabled, so only trace export should be reported as enabled.
    Map<String, Object> startupLog = startupLog(Config.get());

    assertEquals(true, startupLog.get("otlp_traces_export_enabled"));
    assertEquals(false, startupLog.get("otlp_metrics_export_enabled"));
    assertEquals(false, startupLog.get("otlp_logs_export_enabled"));
  }

  @Test
  @WithConfig(key = OtlpConfig.TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = TracerConfig.WRITER_TYPE, value = "DDAgentWriter")
  void tracesNotExportedWhenWriterTypeOverridesOtlpExporter() throws IOException {
    // The OTLP trace exporter is selected, but an explicit dd.writer.type override wins in
    // WriterFactory, so the effective writer is the DDAgentWriter and traces are not exported over
    // OTLP.
    Map<String, Object> startupLog = startupLog(Config.get());

    assertEquals(false, startupLog.get("otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED, value = "true")
  void metricsExportedWhenSpanMetricsEnabled() throws IOException {
    // Span metrics are exported over OTLP via OtlpStatsMetricWriter whenever span metrics are
    // enabled, independently of the OTel metrics signal, so metrics export should be reported.
    Map<String, Object> startupLog = startupLog(Config.get());

    assertEquals(true, startupLog.get("otlp_metrics_export_enabled"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> startupLog(Config config) throws IOException {
    String json =
        new Moshi.Builder().add(new StatusLogger()).build().adapter(Config.class).toJson(config);
    return (Map<String, Object>) new Moshi.Builder().build().adapter(Object.class).fromJson(json);
  }
}
