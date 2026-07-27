package datadog.trace.core;

import static datadog.trace.api.config.OtlpConfig.LOGS_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.LOGS_OTEL_EXPORTER;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_EXPORTER;
import static datadog.trace.api.config.OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.DD_AGENT_WRITER_TYPE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class StatusLoggerTest extends DDJavaSpecification {

  @Test
  void otlpExportDisabledByDefault() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertFalse(flag(startupLog, "otlp_traces_export_enabled"));
    assertFalse(flag(startupLog, "otlp_metrics_export_enabled"));
    assertFalse(flag(startupLog, "otlp_logs_export_enabled"));
  }

  @Test
  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = METRICS_OTEL_ENABLED, value = "true")
  @WithConfig(key = METRICS_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = LOGS_OTEL_ENABLED, value = "true")
  @WithConfig(key = LOGS_OTEL_EXPORTER, value = "otlp")
  void otlpExportEnabledWhenConfigured() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertTrue(flag(startupLog, "otlp_traces_export_enabled"));
    assertTrue(flag(startupLog, "otlp_metrics_export_enabled"));
    assertTrue(flag(startupLog, "otlp_logs_export_enabled"));
  }

  @Test
  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = METRICS_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = LOGS_OTEL_EXPORTER, value = "otlp")
  void metricsAndLogsRequireOtelSignalEnabled() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertTrue(flag(startupLog, "otlp_traces_export_enabled"));
    assertFalse(flag(startupLog, "otlp_metrics_export_enabled"));
    assertFalse(flag(startupLog, "otlp_logs_export_enabled"));
  }

  @Test
  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = WRITER_TYPE, value = DD_AGENT_WRITER_TYPE)
  void tracesNotExportedWhenWriterTypeOverridesOtlpExporter() throws IOException {
    assertFalse(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = WRITER_TYPE, value = "MultiWriter:OtlpWriter,DDAgentWriter")
  void tracesExportedWhenMultiWriterIncludesOtlpWriter() throws IOException {
    assertTrue(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = WRITER_TYPE, value = "MultiWriter:DDAgentWriter,MultiWriter:OtlpWriter")
  void tracesExportedWhenMultiWriterPrefixRepeats() throws IOException {
    assertTrue(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = WRITER_TYPE, value = "MultiWriter:LoggingWriter,DDAgentWriter")
  void tracesNotExportedWhenMultiWriterExcludesOtlpWriter() throws IOException {
    assertFalse(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = WRITER_TYPE, value = "MultiWriter: OtlpWriter")
  void tracesNotExportedWhenMultiWriterSubTypeIsPadded() throws IOException {
    assertFalse(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = WRITER_TYPE, value = "MultiWriter:OtlpWriterExtra")
  void tracesNotExportedWhenMultiWriterSubTypeOnlyPrefixesOtlpWriter() throws IOException {
    assertFalse(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = WRITER_TYPE, value = "DDAgentWriter,OtlpWriter")
  void tracesNotExportedWhenCommaSeparatedWithoutMultiWriterPrefix() throws IOException {
    assertFalse(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = WRITER_TYPE, value = "TraceStructureWriter:/tmp/out,OtlpWriter")
  void tracesNotExportedWhenTraceStructureWriterTakesPrecedence() throws IOException {
    assertFalse(flag(startupLog(), "otlp_traces_export_enabled"));
  }

  @Test
  @WithConfig(key = OTEL_TRACES_SPAN_METRICS_ENABLED, value = "true")
  void metricsExportedWhenSpanMetricsEnabled() throws IOException {
    assertTrue(flag(startupLog(), "otlp_metrics_export_enabled"));
  }

  @Test
  @WithConfig(key = METRICS_OTEL_ENABLED, value = "true")
  @WithConfig(key = METRICS_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = OTEL_TRACES_SPAN_METRICS_ENABLED, value = "false")
  void metricsExportedWhenOtelMetricsSignalEnabledWithoutSpanMetrics() throws IOException {
    assertTrue(flag(startupLog(), "otlp_metrics_export_enabled"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> startupLog() throws IOException {
    String json =
        new Moshi.Builder()
            .add(new StatusLogger())
            .build()
            .adapter(Config.class)
            .toJson(Config.get());
    return (Map<String, Object>) new Moshi.Builder().build().adapter(Object.class).fromJson(json);
  }

  private static boolean flag(Map<String, Object> startupLog, String name) {
    Object value = startupLog.get(name);
    assertTrue(value instanceof Boolean, name + " should be a boolean, was " + value);
    return (Boolean) value;
  }
}
