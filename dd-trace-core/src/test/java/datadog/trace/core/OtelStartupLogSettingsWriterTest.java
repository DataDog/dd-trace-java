package datadog.trace.core;

import static datadog.trace.api.config.OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTLP_TRACES_PROTOCOL;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.api.config.TracerConfig.AGENT_HOST;
import static datadog.trace.core.OtelStartupLogSettingsWriter.REDACTED;
import static datadog.trace.core.StatusLoggerTest.startupLog;
import static datadog.trace.core.StatusLoggerTest.startupLogJson;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.json.JsonMapper;
import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class OtelStartupLogSettingsWriterTest extends DDJavaSpecification {

  @Test
  void otelSettingsWithoutOtlpExport() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertEquals(false, startupLog.get("DD_TRACE_OTEL_ENABLED"));
    assertEquals(false, startupLog.get("DD_METRICS_OTEL_ENABLED"));
    assertEquals(false, startupLog.get("DD_LOGS_OTEL_ENABLED"));
    assertEquals("none", startupLog.get("OTEL_TRACES_EXPORTER"));
    assertEquals("none", startupLog.get("OTEL_METRICS_EXPORTER"));
    assertEquals("none", startupLog.get("OTEL_LOGS_EXPORTER"));
    assertTrue(startupLog.containsKey("OTEL_LOG_LEVEL"));
    assertEquals(Config.get().getServiceName(), startupLog.get("OTEL_SERVICE_NAME"));
    assertEquals("datadog,tracecontext,baggage", startupLog.get("OTEL_PROPAGATORS"));
    assertEquals("datadog_agent_rates", startupLog.get("OTEL_TRACES_SAMPLER"));
    // exporter settings are only reported for the signals exported over OTLP
    for (String key : startupLog.keySet()) {
      assertFalse(key.startsWith("OTEL_EXPORTER_OTLP_"), key);
      assertFalse(key.startsWith("OTEL_BLRP_"), key);
    }
    assertFalse(startupLog.containsKey("OTEL_METRIC_EXPORT_INTERVAL"));
  }

  /** The operator-style configuration of the OTEL_STARTUP_LOGS system-tests scenario. */
  @Test
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "DD_METRICS_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "DD_LOGS_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "otlp", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_EXPORTER_OTLP_PROTOCOL",
      value = "http/protobuf",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_EXPORTER_OTLP_ENDPOINT",
      value = "http://agent:4318",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
      value = "http://agent:4318/v1/traces",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE",
      value = "delta",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_TRACES_SAMPLER",
      value = "parentbased_always_on",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_PROPAGATORS",
      value = "tracecontext,baggage",
      env = true,
      addPrefix = false)
  void otelSettingsOfOperatorConfiguration() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertEquals(true, startupLog.get("DD_TRACE_OTEL_ENABLED"));
    assertEquals(true, startupLog.get("DD_METRICS_OTEL_ENABLED"));
    assertEquals(true, startupLog.get("DD_LOGS_OTEL_ENABLED"));
    assertEquals("otlp", startupLog.get("OTEL_TRACES_EXPORTER"));
    assertEquals("otlp", startupLog.get("OTEL_METRICS_EXPORTER"));
    assertEquals("otlp", startupLog.get("OTEL_LOGS_EXPORTER"));
    assertEquals("tracecontext,baggage", startupLog.get("OTEL_PROPAGATORS"));
    assertEquals("parentbased_always_on", startupLog.get("OTEL_TRACES_SAMPLER"));
    assertFalse(startupLog.containsKey("OTEL_TRACES_SAMPLER_ARG"));

    assertExporter(startupLog, "OTEL_EXPORTER_OTLP_TRACES", "http://agent:4318/v1/traces");
    assertExporter(startupLog, "OTEL_EXPORTER_OTLP_METRICS", "http://agent:4318/v1/metrics");
    assertExporter(startupLog, "OTEL_EXPORTER_OTLP_LOGS", "http://agent:4318/v1/logs");
    assertExporter(startupLog, "OTEL_EXPORTER_OTLP", "http://agent:4318/");

    assertEquals(10_000, number(startupLog, "OTEL_METRIC_EXPORT_INTERVAL"));
    assertEquals("delta", startupLog.get("OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE"));
    assertEquals(1_000, number(startupLog, "OTEL_BLRP_SCHEDULE_DELAY"));
    assertEquals(30_000, number(startupLog, "OTEL_BLRP_EXPORT_TIMEOUT"));
    assertEquals(2048, number(startupLog, "OTEL_BLRP_MAX_QUEUE_SIZE"));
    assertEquals(512, number(startupLog, "OTEL_BLRP_MAX_EXPORT_BATCH_SIZE"));
  }

  @Test
  @WithConfig(key = AGENT_HOST, value = "my-agent")
  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = OTLP_TRACES_PROTOCOL, value = "grpc")
  void exporterEndpointsDefaultToAgentHost() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertEquals("http://my-agent:4317/", startupLog.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
    assertEquals("grpc", startupLog.get("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"));
    // OpenTelemetry settings are ignored, so the general settings keep their defaults
    assertEquals("http://my-agent:4318/", startupLog.get("OTEL_EXPORTER_OTLP_ENDPOINT"));
    assertEquals("http/protobuf", startupLog.get("OTEL_EXPORTER_OTLP_PROTOCOL"));
  }

  @Test
  @WithConfig(key = AGENT_HOST, value = "my-agent")
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "otlp", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_EXPORTER_OTLP_PROTOCOL", value = "grpc", env = true, addPrefix = false)
  void generalGrpcEndpointDefaultsToAgentGrpcPort() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertEquals("http://my-agent:4317/", startupLog.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
    assertEquals("grpc", startupLog.get("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"));
    assertEquals("http://my-agent:4317/", startupLog.get("OTEL_EXPORTER_OTLP_ENDPOINT"));
    assertEquals("grpc", startupLog.get("OTEL_EXPORTER_OTLP_PROTOCOL"));
  }

  @TableTest({
    "scenario            | endpoint                                                                   | sanitized                       ",
    "user info and query | 'https://user:secret-password@collector:4318/v1/traces?token=secret-token' | https://collector:4318/v1/traces",
    "leading space       | ' http:///user:secret-password@collector:4318/v1/traces'                   | http://collector:4318/v1/traces "
  })
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "otlp", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_EXPORTER_OTLP_HEADERS",
      value = "api-key=secret-key,other=secret-other",
      env = true,
      addPrefix = false)
  void credentialsAreNotLogged(String endpoint, String sanitized) throws IOException {
    injectEnvConfig("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", endpoint, false);
    String json = startupLogJson();
    Map<String, Object> startupLog = JsonMapper.fromJsonToMap(json);

    assertFalse(json.contains("secret"), json);
    Map<String, Object> redactedHeaders = new HashMap<>();
    redactedHeaders.put("api-key", REDACTED);
    redactedHeaders.put("other", REDACTED);
    assertEquals(redactedHeaders, startupLog.get("OTEL_EXPORTER_OTLP_TRACES_HEADERS"));
    assertEquals(redactedHeaders, startupLog.get("OTEL_EXPORTER_OTLP_HEADERS"));
    assertEquals(sanitized, startupLog.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
  }

  @Test
  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = OTEL_TRACES_SPAN_METRICS_ENABLED, value = "true")
  void spanMetricsDoNotReportMeterProviderSettings() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertTrue(startupLog.containsKey("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"));
    assertEquals("none", startupLog.get("OTEL_METRICS_EXPORTER"));
    assertFalse(startupLog.containsKey("OTEL_METRIC_EXPORT_INTERVAL"));
    assertFalse(startupLog.containsKey("OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE"));
  }

  @Test
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_RESOURCE_ATTRIBUTES",
      value =
          "service.name=my-service,deployment.environment.name=prod,service.version=1.2,team=otel",
      env = true,
      addPrefix = false)
  void resourceAttributesAreTheExportedResource() throws IOException {
    Map<String, Object> startupLog = startupLog();

    assertEquals("my-service", startupLog.get("OTEL_SERVICE_NAME"));
    Map<?, ?> resource = (Map<?, ?>) startupLog.get("OTEL_RESOURCE_ATTRIBUTES");
    assertEquals("my-service", resource.get("service.name"));
    assertEquals("prod", resource.get("deployment.environment.name"));
    assertEquals("1.2", resource.get("service.version"));
    assertEquals("otel", resource.get("team"));
    assertEquals("java", resource.get("telemetry.sdk.language"));
  }

  @TableTest({
    "scenario        | key                     | value                                       | sampler                      | arg  | rateLimit",
    "no settings     |                         |                                             | datadog_agent_rates          |      |          ",
    "rate 0          | trace.sample.rate       | 0                                           | parentbased_always_off       |      | 100      ",
    "rate 1          | trace.sample.rate       | 1                                           | parentbased_always_on        |      | 100      ",
    "ratio           | trace.sample.rate       | 0.25                                        | parentbased_traceidratio     | 0.25 | 100      ",
    "rules           | trace.sampling.rules    | '[{\"service\":\"a\",\"sample_rate\":0.5}]' | datadog_custom_rules         |      | 100      ",
    "OTLP export     | trace.otel.exporter     | otlp                                        | parentbased_always_on        |      |          ",
    "force keep      | priority.sampling.force | keep                                        | parentbased_always_on        |      |          ",
    "force drop      | priority.sampling.force | drop                                        | parentbased_always_off       |      |          ",
    "no priority     | priority.sampling       | false                                       | always_on                    |      |          ",
    "APM tracing off | apm.tracing.enabled     | false                                       | datadog_apm_tracing_disabled |      |          "
  })
  void samplerIsTheRootSampler(
      String key, String value, String sampler, Double arg, Integer rateLimit) throws IOException {
    if (key != null) {
      injectSysConfig(key, value);
    }
    Map<String, Object> startupLog = startupLog();

    assertEquals(sampler, startupLog.get("OTEL_TRACES_SAMPLER"));
    Object samplerArg = startupLog.get("OTEL_TRACES_SAMPLER_ARG");
    assertEquals(arg, samplerArg == null ? null : ((Number) samplerArg).doubleValue());
    // only the traces kept by rules are rate limited
    Object samplerRateLimit = startupLog.get("DD_TRACE_RATE_LIMIT");
    assertEquals(
        rateLimit, samplerRateLimit == null ? null : ((Number) samplerRateLimit).intValue());
  }

  @TableTest({
    "scenario         | inject                | extract               | behavior | propagators         ",
    "same styles      | tracecontext,baggage  | tracecontext,baggage  |          | tracecontext,baggage",
    "OTel names       | b3single,b3multi,xray | b3single,b3multi,xray |          | b3,b3multi,xray     ",
    "different styles | datadog               | tracecontext          |          |                     ",
    "different order  | datadog,tracecontext  | tracecontext,datadog  |          |                     ",
    "extract ignored  | datadog               | datadog               | ignore   |                     ",
    "none             | none                  | none                  |          | none                "
  })
  void propagatorsDescribeInjectionAndExtraction(
      String inject, String extract, String behavior, String propagators) throws IOException {
    injectSysConfig("trace.propagation.style.inject", inject);
    injectSysConfig("trace.propagation.style.extract", extract);
    if (behavior != null) {
      injectSysConfig("trace.propagation.behavior.extract", behavior);
    }

    assertEquals(propagators, startupLog().get("OTEL_PROPAGATORS"));
  }

  @TableTest({
    "scenario      | endpoint                                        | sanitized                           ",
    "plain         | http://agent:4318/v1/traces                     | http://agent:4318/v1/traces         ",
    "no path       | http://agent:4318                               | http://agent:4318/                  ",
    "user info     | https://user:password@collector:4318/v1/traces  | https://collector:4318/v1/traces    ",
    "user only     | http://user@collector                           | http://collector/                   ",
    "query         | http://collector:4318/v1/traces?token=secret    | http://collector:4318/v1/traces     ",
    "fragment      | http://collector:4318/v1/traces#secret          | http://collector:4318/v1/traces     ",
    "@ in path     | http://collector:4318/v1/traces@path            | http://collector:4318/v1/traces@path",
    "extra slash   | http:///user:secret@collector:4318/v1/traces    | http://collector:4318/v1/traces     ",
    "no slash      | https:user:secret@collector:4318/v1/traces      | https://collector:4318/v1/traces    ",
    "backslashes   | 'http:\\\\user:secret@collector:4318/v1/traces' | http://collector:4318/v1/traces     ",
    "upper case    | HTTP:////user:secret@collector:4318             | http://collector:4318/              ",
    "leading space | ' http:///user:secret@collector:4318/v1/traces' | http://collector:4318/v1/traces     ",
    "unix socket   | unix:///var/run/otlp.sock                       | unix:///var/run/otlp.sock           ",
    "no scheme     | user:secret@collector:4317                      | <redacted>                          ",
    "other scheme  | ftp://user:secret@collector                     | <redacted>                          "
  })
  void endpointsDropCredentials(String endpoint, String sanitized) {
    assertEquals(sanitized, OtelStartupLogSettingsWriter.sanitizeEndpoint(endpoint));
  }

  private static void assertExporter(
      Map<String, Object> startupLog, String prefix, String endpoint) {
    assertEquals(endpoint, startupLog.get(prefix + "_ENDPOINT"));
    assertEquals("http/protobuf", startupLog.get(prefix + "_PROTOCOL"));
    assertEquals(emptyMap(), startupLog.get(prefix + "_HEADERS"));
  }

  private static int number(Map<String, Object> startupLog, String key) {
    Object value = startupLog.get(key);
    assertTrue(value instanceof Number, key + " should be a number, was " + value);
    return ((Number) value).intValue();
  }
}
