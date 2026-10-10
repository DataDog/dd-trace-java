package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.LOG_LEVEL;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(WithConfigExtension.class)
class OtelEnvironmentConfigSourceTest {

  private static final String REQUEST_HEADER_TAGS_VALUE =
      "content-type:http.request.header.content-type,custom-header:http.request.header.custom-header";
  private static final String RESPONSE_HEADER_TAGS_VALUE =
      "content-length:http.response.header.content-length,another-header:http.response.header.another-header";
  private static final String TAGS_VALUE =
      "key1:one,key2:two,key3:three,key4:four,key5:five,key6:six,key7:seven,key8:eight,key9:nine,key10:ten,key11:eleven,key12:twelve";

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
}
