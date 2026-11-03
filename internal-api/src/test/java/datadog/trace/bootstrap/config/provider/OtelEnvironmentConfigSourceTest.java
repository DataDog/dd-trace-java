package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.LOG_LEVEL;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.OtlpConfig.LOGS_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.LOGS_OTEL_EXPORTER;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_EXPORTER;
import static datadog.trace.api.config.OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTLP_LOGS_TIMEOUT;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_TIMEOUT;
import static datadog.trace.api.config.OtlpConfig.OTLP_TRACES_TIMEOUT;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXTENSIONS_PATH;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_PROPAGATION_STYLE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeSysConfig;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class OtelEnvironmentConfigSourceTest {

  private static final String REQUEST_HEADER_TAGS_VALUE =
      "content-type:http.request.header.content-type,custom-header:http.request.header.custom-header";
  private static final String RESPONSE_HEADER_TAGS_VALUE =
      "content-length:http.response.header.content-length,another-header:http.response.header.another-header";
  private static final String TAGS_VALUE =
      "key1:one,key2:two,key3:three,key4:four,key5:five,key6:six,key7:seven,key8:eight,key9:nine,key10:ten,key11:eleven,key12:twelve";

  private static final String OTLP_TIMEOUT = "500";
  private static final List<String> SIGNALS = asList("traces", "metrics", "logs");

  @TempDir Path tempDir;

  // ignore this test when we enable the OpenTelemetry integration by default
  @Test
  @WithConfig(key = "otel.service.name", value = "TEST_SERVICE", addPrefix = false)
  @WithConfig(key = "otel.propagators", value = "xray,b3,datadog", addPrefix = false)
  @WithConfig(key = "otel.traces.sampler", value = "parentbased_traceidratio", addPrefix = false)
  @WithConfig(key = "otel.traces.sampler.arg", value = "0.5", addPrefix = false)
  @WithConfig(key = "otel.traces.exporter", value = "none", addPrefix = false)
  @WithConfig(key = "otel.metrics.exporter", value = "none", addPrefix = false)
  @WithConfig(key = "otel.logs.exporter", value = "none", addPrefix = false)
  @WithConfig(
      key = "otel.resource.attributes",
      value = "service.name=DEV_SERVICE",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.client.capture-request-headers",
      value = "content-type",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.client.capture-response-headers",
      value = "content-length",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.server.capture-request-headers",
      value = "custom-header",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.server.capture-response-headers",
      value = "another-header",
      addPrefix = false)
  @WithConfig(
      key = "otel.javaagent.extensions",
      value = "/opt/opentelemetry/extensions",
      addPrefix = false)
  void noOtelSystemPropertiesAreMappedByDefault() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(TRACE_OTEL_ENABLED));
    assertNoOtelSettingsMapped(source);
  }

  // ignore this test when we enable the OpenTelemetry integration by default
  @Test
  @WithConfig(key = "OTEL_LOG_LEVEL", value = "debug", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_SERVICE_NAME", value = "TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_PROPAGATORS", value = "xray,b3,datadog", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_TRACES_SAMPLER",
      value = "parentbased_traceidratio",
      env = true,
      addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_SAMPLER_ARG", value = "0.5", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_METRICS_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_LOGS_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_RESOURCE_ATTRIBUTES",
      value = "service.name=DEV_SERVICE",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS",
      value = "content-type",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_RESPONSE_HEADERS",
      value = "content-length",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_REQUEST_HEADERS",
      value = "custom-header",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_RESPONSE_HEADERS",
      value = "another-header",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_JAVAAGENT_EXTENSIONS",
      value = "/opt/opentelemetry/extensions",
      env = true,
      addPrefix = false)
  void noOtelEnvironmentVariablesAreMappedByDefault() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(TRACE_OTEL_ENABLED));
    assertNoOtelSettingsMapped(source);
  }

  @Disabled("enable this test when we enable the OpenTelemetry integration by default")
  @Test
  @WithConfig(key = "otel.sdk.disabled", value = "true", addPrefix = false)
  @WithConfig(key = "otel.service.name", value = "TEST_SERVICE", addPrefix = false)
  @WithConfig(key = "otel.propagators", value = "xray,b3,datadog", addPrefix = false)
  @WithConfig(key = "otel.traces.sampler", value = "parentbased_traceidratio", addPrefix = false)
  @WithConfig(key = "otel.traces.sampler.arg", value = "0.5", addPrefix = false)
  @WithConfig(key = "otel.traces.exporter", value = "none", addPrefix = false)
  @WithConfig(key = "otel.metrics.exporter", value = "none", addPrefix = false)
  @WithConfig(key = "otel.logs.exporter", value = "none", addPrefix = false)
  @WithConfig(
      key = "otel.resource.attributes",
      value = "service.name=DEV_SERVICE",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.client.capture-request-headers",
      value = "content-type",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.client.capture-response-headers",
      value = "content-length",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.server.capture-request-headers",
      value = "custom-header",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.server.capture-response-headers",
      value = "another-header",
      addPrefix = false)
  @WithConfig(
      key = "otel.javaagent.extensions",
      value = "/opt/opentelemetry/extensions",
      addPrefix = false)
  void disablingOtelWithSystemPropertyDisablesOtelIntegration() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("false", source.get(TRACE_OTEL_ENABLED));
    assertNoOtelSettingsMapped(source);
  }

  @Disabled("enable this test when we enable the OpenTelemetry integration by default")
  @Test
  @WithConfig(key = "OTEL_SDK_DISABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_LOG_LEVEL", value = "debug", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_SERVICE_NAME", value = "TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_PROPAGATORS", value = "xray,b3,datadog", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_TRACES_SAMPLER",
      value = "parentbased_traceidratio",
      env = true,
      addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_SAMPLER_ARG", value = "0.5", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_METRICS_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_LOGS_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_RESOURCE_ATTRIBUTES",
      value = "service.name=DEV_SERVICE",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS",
      value = "content-type",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_RESPONSE_HEADERS",
      value = "content-length",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_REQUEST_HEADERS",
      value = "custom-header",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_RESPONSE_HEADERS",
      value = "another-header",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_JAVAAGENT_EXTENSIONS",
      value = "/opt/opentelemetry/extensions",
      env = true,
      addPrefix = false)
  void disablingOtelWithEnvironmentVariableDisablesOtelIntegration() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("false", source.get(TRACE_OTEL_ENABLED));
    assertNoOtelSettingsMapped(source);
  }

  @Test
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  @WithConfig(key = "otel.service.name", value = "TEST_SERVICE", addPrefix = false)
  @WithConfig(key = "otel.propagators", value = "xray,b3,datadog", addPrefix = false)
  @WithConfig(key = "otel.traces.sampler", value = "parentbased_traceidratio", addPrefix = false)
  @WithConfig(key = "otel.traces.sampler.arg", value = "0.5", addPrefix = false)
  @WithConfig(key = "otel.traces.exporter", value = "none", addPrefix = false)
  @WithConfig(key = "otel.metrics.exporter", value = "none", addPrefix = false)
  @WithConfig(key = "otel.logs.exporter", value = "none", addPrefix = false)
  @WithConfig(
      key = "otel.resource.attributes",
      value = "service.name=DEV_SERVICE",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.client.capture-request-headers",
      value = "content-type",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.client.capture-response-headers",
      value = "content-length",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.server.capture-request-headers",
      value = "custom-header",
      addPrefix = false)
  @WithConfig(
      key = "otel.instrumentation.http.server.capture-response-headers",
      value = "another-header",
      addPrefix = false)
  @WithConfig(
      key = "otel.javaagent.extensions",
      value = "/opt/opentelemetry/extensions",
      addPrefix = false)
  void otelSystemPropertiesAreMappedWhenOtelIsEnabled() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    // value from otel.service.name overrides the one from otel.resource.attributes
    assertEquals("TEST_SERVICE", source.get(SERVICE_NAME));
    assertEquals("xray,b3single,datadog", source.get(TRACE_PROPAGATION_STYLE));
    assertEquals("0.5", source.get(TRACE_SAMPLE_RATE));
    assertEquals("false", source.get(TRACE_ENABLED));
    assertEquals("false", source.get(RUNTIME_METRICS_ENABLED));
    assertEquals(REQUEST_HEADER_TAGS_VALUE, source.get(REQUEST_HEADER_TAGS));
    assertEquals(RESPONSE_HEADER_TAGS_VALUE, source.get(RESPONSE_HEADER_TAGS));
    assertEquals("/opt/opentelemetry/extensions", source.get(TRACE_EXTENSIONS_PATH));
  }

  @Test
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_LOG_LEVEL", value = "debug", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_SERVICE_NAME", value = "TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_PROPAGATORS", value = "xray,b3,datadog", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_TRACES_SAMPLER",
      value = "parentbased_traceidratio",
      env = true,
      addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_SAMPLER_ARG", value = "0.5", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_METRICS_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_LOGS_EXPORTER", value = "none", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_RESOURCE_ATTRIBUTES",
      value = "service.name=DEV_SERVICE",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS",
      value = "content-type",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_RESPONSE_HEADERS",
      value = "content-length",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_REQUEST_HEADERS",
      value = "custom-header",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_RESPONSE_HEADERS",
      value = "another-header",
      env = true,
      addPrefix = false)
  @WithConfig(
      key = "OTEL_JAVAAGENT_EXTENSIONS",
      value = "/opt/opentelemetry/extensions",
      env = true,
      addPrefix = false)
  void otelEnvironmentVariablesAreMappedWhenOtelIsEnabled() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("debug", source.get(LOG_LEVEL));
    // value from OTEL_SERVICE_NAME overrides the one from OTEL_RESOURCE_ATTRIBUTES
    assertEquals("TEST_SERVICE", source.get(SERVICE_NAME));
    assertEquals("xray,b3single,datadog", source.get(TRACE_PROPAGATION_STYLE));
    assertEquals("0.5", source.get(TRACE_SAMPLE_RATE));
    assertEquals("false", source.get(TRACE_ENABLED));
    assertEquals("false", source.get(RUNTIME_METRICS_ENABLED));
    assertEquals(REQUEST_HEADER_TAGS_VALUE, source.get(REQUEST_HEADER_TAGS));
    assertEquals(RESPONSE_HEADER_TAGS_VALUE, source.get(RESPONSE_HEADER_TAGS));
    assertEquals("/opt/opentelemetry/extensions", source.get(TRACE_EXTENSIONS_PATH));
  }

  @Test
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  @WithConfig(key = "otel.traces.exporter", value = "otlp", addPrefix = false)
  void otelTracesExporterOtlpSystemPropertyIsMapped() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(TRACE_ENABLED));
    assertEquals("otlp", source.get(TRACE_OTEL_EXPORTER));
  }

  @Test
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "otlp", env = true, addPrefix = false)
  void otelTracesExporterOtlpEnvironmentVariableIsMapped() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(TRACE_ENABLED));
    assertEquals("otlp", source.get(TRACE_OTEL_EXPORTER));
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  void otelTracesSpanMetricsEnabledSystemPropertyIsMappedWhenOtelIsEnabled(String value) {
    injectSysConfig(OTEL_TRACES_SPAN_METRICS_ENABLED, value, false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals(value, source.get(OTEL_TRACES_SPAN_METRICS_ENABLED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  void otelTracesSpanMetricsEnabledEnvironmentVariableIsMappedWhenOtelIsEnabled(String value) {
    injectEnvConfig("OTEL_TRACES_SPAN_METRICS_ENABLED", value, false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals(value, source.get(OTEL_TRACES_SPAN_METRICS_ENABLED));
  }

  @Test
  // Without dd.trace.otel.enabled, setupTraceOtelEnvironment() does not run.
  @WithConfig(key = OTEL_TRACES_SPAN_METRICS_ENABLED, value = "true", addPrefix = false)
  void otelTracesSpanMetricsEnabledIsNotMappedWhenOtelIsDisabled() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertNull(source.get(OTEL_TRACES_SPAN_METRICS_ENABLED));
  }

  @Test
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_TRACES_EXPORTER", value = "none", env = true, addPrefix = false)
  void otelTracesExporterNoneStillDisablesTracing() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("false", source.get(TRACE_ENABLED));
  }

  @TableTest({
    "scenario              | exporters      | DD_TRACE_OTEL_ENABLED=true | DD_TRACE_OTEL_ENABLED=false",
    "unset                 |                | DATADOG                    | DATADOG                    ",
    "empty                 | ''             | DATADOG                    | DATADOG                    ",
    "otlp                  | otlp           | OTLP                       | DATADOG                    ",
    "otlp with unsupported | 'otlp,console' | OTLP                       | DATADOG                    ",
    "none                  | none           | DISABLED                   | DATADOG                    ",
    // none combined with another exporter is not expected input from a customer
    "none with otlp        | 'none,otlp'    | OTLP                       | DATADOG                    "
  })
  // keeps the OpenTelemetry mapping active when DD_TRACE_OTEL_ENABLED=false
  @WithConfig(key = METRICS_OTEL_ENABLED, value = "true")
  void otelTracesExporterSelection(
      String exporters, Export traceOtelEnabled, Export traceOtelDisabled) {
    injectExporterConfig("traces", exporters);

    injectSysConfig(TRACE_OTEL_ENABLED, "true");
    assertEquals(traceOtelEnabled, tracesExport(new OtelEnvironmentConfigSource()));

    injectSysConfig(TRACE_OTEL_ENABLED, "false");
    assertEquals(traceOtelDisabled, tracesExport(new OtelEnvironmentConfigSource()));
  }

  @TableTest({
    "scenario              | exporters      | DD_METRICS_OTEL_ENABLED=true | DD_METRICS_OTEL_ENABLED=false",
    "unset                 |                | OTLP                         | DATADOG                      ",
    "empty                 | ''             | OTLP                         | DATADOG                      ",
    "otlp                  | otlp           | OTLP                         | DATADOG                      ",
    "otlp with unsupported | 'otlp,console' | OTLP                         | DATADOG                      ",
    "none                  | none           | DISABLED                     | DISABLED                     ",
    // none combined with another exporter is not expected input from a customer
    "none with otlp        | 'none,otlp'    | OTLP                         | DATADOG                      "
  })
  // keeps the OpenTelemetry mapping active when DD_METRICS_OTEL_ENABLED=false
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  void otelMetricsExporterSelection(
      String exporters, Export metricsOtelEnabled, Export metricsOtelDisabled) {
    injectExporterConfig("metrics", exporters);

    injectSysConfig(METRICS_OTEL_ENABLED, "true");
    assertEquals(metricsOtelEnabled, metricsExport(new OtelEnvironmentConfigSource()));

    injectSysConfig(METRICS_OTEL_ENABLED, "false");
    assertEquals(metricsOtelDisabled, metricsExport(new OtelEnvironmentConfigSource()));
  }

  @TableTest({
    "scenario              | exporters      | DD_LOGS_OTEL_ENABLED=true | DD_LOGS_OTEL_ENABLED=false",
    "unset                 |                | OTLP                      | DISABLED                  ",
    "empty                 | ''             | OTLP                      | DISABLED                  ",
    "otlp                  | otlp           | OTLP                      | DISABLED                  ",
    "otlp with unsupported | 'otlp,console' | OTLP                      | DISABLED                  ",
    "none                  | none           | DISABLED                  | DISABLED                  ",
    // none combined with another exporter is not expected input from a customer
    "none with otlp        | 'none,otlp'    | OTLP                      | DISABLED                  "
  })
  // keeps the OpenTelemetry mapping active when DD_LOGS_OTEL_ENABLED=false
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  void otelLogsExporterSelection(
      String exporters, Export logsOtelEnabled, Export logsOtelDisabled) {
    injectExporterConfig("logs", exporters);

    injectSysConfig(LOGS_OTEL_ENABLED, "true");
    assertEquals(logsOtelEnabled, logsExport(new OtelEnvironmentConfigSource()));

    injectSysConfig(LOGS_OTEL_ENABLED, "false");
    assertEquals(logsOtelDisabled, logsExport(new OtelEnvironmentConfigSource()));
  }

  @TableTest({
    "scenario            | exporters       ",
    "unsupported         | console         ",
    "invalid             | not-an-exporter ",
    "several unsupported | 'zipkin,console'"
  })
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  @WithConfig(key = METRICS_OTEL_ENABLED, value = "true")
  @WithConfig(key = LOGS_OTEL_ENABLED, value = "true")
  void onlyUnsupportedExportersUseDefaultExport(String exporters) {
    injectExporterConfig("traces", exporters);
    injectExporterConfig("metrics", exporters);
    injectExporterConfig("logs", exporters);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals(Export.DATADOG, tracesExport(source));
    assertEquals(Export.OTLP, metricsExport(source));
    assertEquals(Export.OTLP, logsExport(source));
  }

  @TableTest({
    "scenario            | signal  | exporters       ",
    "unsupported         | traces  | console         ",
    "invalid             | metrics | not-an-exporter ",
    "several unsupported | logs    | 'zipkin,console'"
  })
  void onlyUnsupportedExporterListIsUnset(String signal, String exporters) {
    assertSameMapping(signal, exporters, null);
  }

  @TableTest({
    "scenario        | signal  | exporters | equivalent",
    "otlp upper case | traces  | OTLP      | otlp      ",
    "otlp mixed case | metrics | oTlP      | otlp      ",
    "none upper case | logs    | NONE      | none      "
  })
  void exporterNamesAreCaseInsensitive(String signal, String exporters, String equivalent) {
    assertSameMapping(signal, exporters, equivalent);
  }

  @TableTest({
    "scenario           | signal  | exporters          | equivalent    ",
    "padded otlp        | traces  | ' otlp '           | otlp          ",
    "padded none        | metrics | 'none '            | none          ",
    "padded list values | logs    | ' otlp , console ' | 'otlp,console'"
  })
  void exporterNamesAreTrimmed(String signal, String exporters, String equivalent) {
    assertSameMapping(signal, exporters, equivalent);
  }

  @TableTest({
    "scenario          | signal  | exporters       | equivalent    ",
    "trailing comma    | traces  | 'otlp,'         | otlp          ",
    "leading comma     | metrics | ',none'         | none          ",
    "consecutive comma | logs    | 'otlp,,console' | 'otlp,console'"
  })
  void emptyExporterEntriesAreSkipped(String signal, String exporters, String equivalent) {
    assertSameMapping(signal, exporters, equivalent);
  }

  @TableTest({
    "scenario        | signal  | exporters",
    "empty           | traces  | ''       ",
    "blank           | metrics | '  '     ",
    "only separators | logs    | ', ,'    "
  })
  void blankExporterListIsUnset(String signal, String exporters) {
    assertSameMapping(signal, exporters, null);
  }

  @TableTest({
    "scenario                          | sysProp | envVar | configFile | logsOtelExporter",
    "system property otlp over env     | otlp    | none   |            | otlp            ",
    "system property none over env     | none    | otlp   |            |                 ",
    "empty system property over env    | ''      | none   |            | otlp            ",
    "env over config file              |         | otlp   | none       | otlp            ",
    "config file when nothing else set |         |        | none       |                 "
  })
  @WithConfig(key = LOGS_OTEL_ENABLED, value = "true")
  void otelLogsExporterFollowsOtelPrecedence(
      String sysProp, String envVar, String configFile, String logsOtelExporter)
      throws IOException {
    if (sysProp != null) {
      injectSysConfig("otel.logs.exporter", sysProp, false);
    }
    if (envVar != null) {
      injectEnvConfig("OTEL_LOGS_EXPORTER", envVar, false);
    }
    if (configFile != null) {
      Path file = tempDir.resolve("otel.properties");
      Files.write(file, singletonList("otel.logs.exporter=" + configFile));
      injectSysConfig("otel.javaagent.configuration-file", file.toString(), false);
    }

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals(logsOtelExporter, source.get(LOGS_OTEL_EXPORTER));
  }

  @Test
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  @WithConfig(
      key = "otel.resource.attributes",
      value =
          "key1=one,"
              + "key2=two,"
              + "key3=three,"
              + "service.name=DEV_SERVICE,"
              + "key4=four,"
              + "key5=five,"
              + "key6=six,"
              + "deployment.environment.name=staging,"
              + "key7=seven,"
              + "key8=eight,"
              + "key9=nine,"
              + "service.version=42,"
              + "key10=ten,"
              + "key11=eleven,"
              + "key12=twelve",
      addPrefix = false)
  void otelResourceAttributesSystemPropertyIsMapped() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("DEV_SERVICE", source.get(SERVICE_NAME));
    assertEquals("staging", source.get(ENV));
    assertEquals("42", source.get(VERSION));
    assertEquals(TAGS_VALUE, source.get(TAGS));
  }

  @Test
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(
      key = "OTEL_RESOURCE_ATTRIBUTES",
      value =
          "key1=one,"
              + "key2=two,"
              + "key3=three,"
              + "service.name=DEV_SERVICE,"
              + "key4=four,"
              + "key5=five,"
              + "key6=six,"
              + "deployment.environment=staging,"
              + "key7=seven,"
              + "key8=eight,"
              + "key9=nine,"
              + "service.version=42,"
              + "key10=ten,"
              + "key11=eleven,"
              + "key12=twelve",
      env = true,
      addPrefix = false)
  void otelResourceAttributesEnvironmentVariableIsMapped() {
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("DEV_SERVICE", source.get(SERVICE_NAME));
    assertEquals("staging", source.get(ENV));
    assertEquals("42", source.get(VERSION));
    assertEquals(TAGS_VALUE, source.get(TAGS));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "deployment.environment.name=production,deployment.environment=staging",
        "deployment.environment=staging,deployment.environment.name=production"
      })
  @WithConfig(key = TRACE_OTEL_ENABLED, value = "true")
  void namedDeploymentEnvironmentTakesPrecedenceOverLegacyAttribute(String resourceAttributes) {
    injectSysConfig("otel.resource.attributes", resourceAttributes, false);

    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();

    assertEquals("production", source.get(ENV));
    assertNull(source.get(TAGS));
  }

  private static void assertNoOtelSettingsMapped(OtelEnvironmentConfigSource source) {
    assertNull(source.get(LOG_LEVEL));
    assertNull(source.get(SERVICE_NAME));
    assertNull(source.get(VERSION));
    assertNull(source.get(ENV));
    assertNull(source.get(TAGS));
    assertNull(source.get(TRACE_PROPAGATION_STYLE));
    assertNull(source.get(TRACE_SAMPLE_RATE));
    assertNull(source.get(TRACE_ENABLED));
    assertNull(source.get(RUNTIME_METRICS_ENABLED));
    assertNull(source.get(REQUEST_HEADER_TAGS));
    assertNull(source.get(RESPONSE_HEADER_TAGS));
    assertNull(source.get(TRACE_EXTENSIONS_PATH));
  }

  /** Outcome of an OpenTelemetry exporter setting for one signal. */
  enum Export {
    /** Exported over OTLP. */
    OTLP,
    /** Not exported over OTLP; Datadog collection keeps its default. */
    DATADOG,
    /** Not collected. */
    DISABLED
  }

  /** Sets the exporter list, plus an OTLP timeout that is only mapped when OTLP is selected. */
  private static void injectExporterConfig(String signal, String exporters) {
    injectSysConfig("otel.exporter.otlp." + signal + ".timeout", OTLP_TIMEOUT, false);
    String sysProp = "otel." + signal + ".exporter";
    if (exporters == null) {
      removeSysConfig(sysProp, false);
    } else {
      injectSysConfig(sysProp, exporters, false);
    }
  }

  private static Export tracesExport(OtelEnvironmentConfigSource source) {
    return collectionExport(source, TRACE_OTEL_EXPORTER, OTLP_TRACES_TIMEOUT, TRACE_ENABLED);
  }

  private static Export metricsExport(OtelEnvironmentConfigSource source) {
    return collectionExport(
        source, METRICS_OTEL_EXPORTER, OTLP_METRICS_TIMEOUT, RUNTIME_METRICS_ENABLED);
  }

  private static Export logsExport(OtelEnvironmentConfigSource source) {
    boolean otlp = isOtlpExported(source, LOGS_OTEL_EXPORTER, OTLP_LOGS_TIMEOUT);
    return otlp ? Export.OTLP : Export.DISABLED;
  }

  private static Export collectionExport(
      OtelEnvironmentConfigSource source,
      String exporterKey,
      String otlpTimeoutKey,
      String collectionEnabledKey) {
    if (isOtlpExported(source, exporterKey, otlpTimeoutKey)) {
      return Export.OTLP;
    }
    boolean collectionDisabled = "false".equals(source.get(collectionEnabledKey));
    return collectionDisabled ? Export.DISABLED : Export.DATADOG;
  }

  /** Also checks that OTLP settings are mapped only alongside the OTLP exporter. */
  private static boolean isOtlpExported(
      OtelEnvironmentConfigSource source, String exporterKey, String otlpTimeoutKey) {
    boolean otlp = "otlp".equals(source.get(exporterKey));
    String expectedTimeout = otlp ? OTLP_TIMEOUT : null;
    assertEquals(expectedTimeout, source.get(otlpTimeoutKey));
    return otlp;
  }

  /** Asserts that both exporter lists of the signal map to the same outcome on every signal. */
  private static void assertSameMapping(String signal, String exporters, String equivalent) {
    injectSysConfig(TRACE_OTEL_ENABLED, "true");
    injectSysConfig(METRICS_OTEL_ENABLED, "true");
    injectSysConfig(LOGS_OTEL_ENABLED, "true");
    for (String otherSignal : SIGNALS) {
      injectExporterConfig(otherSignal, null);
    }

    assertEquals(mappedExports(signal, equivalent), mappedExports(signal, exporters));
  }

  private static List<Export> mappedExports(String signal, String exporters) {
    injectExporterConfig(signal, exporters);
    OtelEnvironmentConfigSource source = new OtelEnvironmentConfigSource();
    return asList(tracesExport(source), metricsExport(source), logsExport(source));
  }
}
