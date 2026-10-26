package datadog.trace.api.telemetry;

import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.telemetry.OtelEnvMetricCollectorImpl.OtelEnvMetric;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

// PLEASE READ
// When a metric is generated, it's duplicated in these tests. We call twice setupOteEnvironment()
// because of the separation of the configuration done in the rebuild function of
// DDJavaSpecification between datadog/trace/api/InstrumenterConfig.java and
// internal-api/src/main/java/datadog/trace/api/Config.java.
// We are calling ConfigProvider.createDefault()) twice
// We check otel configuration keys only if opentelemetry is enabled using
// DD_TRACE_OTEL_ENABLED=true or OTEL_SDK_DISABLED=false.
// Note that if DD_TRACE_OTEL_ENABLED is set, its value will overwrite the one in
// OTEL_SDK_DISABLED.
class OtelEnvMetricCollectorImplTest extends DDJavaSpecification {

  @Test
  @WithConfig(key = "DD_SERVICE_NAME", value = "DD_TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_SERVICE_NAME", value = "OTEL_TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "false", env = true, addPrefix = false)
  void otelDisabledNoMetric() {
    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertEquals(0, metrics.size());
  }

  @Test
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_SDK_DISABLED", value = "true", env = true, addPrefix = false)
  void otelSdkDisabledHiding() {
    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertDuplicatedMetric(
        metrics,
        "count",
        1,
        "tracers",
        "otel.env.hiding",
        asList("config_opentelemetry:otel_sdk_disabled", "config_datadog:dd_trace_otel_enabled"));
  }

  @Test
  @WithConfig(key = "OTEL_SERVICE_NAME", value = "OTEL_TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  void otelServiceNameOnlyNoMetric() {
    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertEquals(0, metrics.size());
  }

  @Test
  @WithConfig(key = "DD_SERVICE_NAME", value = "DD_TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_SERVICE_NAME", value = "OTEL_TEST_SERVICE", env = true, addPrefix = false)
  @WithConfig(key = "OTEL_PROPAGATORS", value = "MyStyle", env = true, addPrefix = false)
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  void multipleMetrics() {
    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertEquals(4, metrics.size());
  }

  @TableTest({
    "scenario         | otelEnvKey                                                | otelEnvValue                  | ddEnvKey                      | ddEnvValue              | metricType | metricValue | metricNamespace | metricName      | tagsOtelValue                                                                  | tagsDdValue                                 ",
    "service name     | DD_SERVICE_NAME                                           | DD_TEST_SERVICE               | OTEL_SERVICE_NAME             | OTEL_TEST_SERVICE       | count      | 1           | tracers         | otel.env.hiding | config_opentelemetry:otel_service_name                                         | config_datadog:dd_service_name              ",
    "log level        | OTEL_LOG_LEVEL                                            | debug                         | DD_LOG_LEVEL                  | INFO                    | count      | 1           | tracers         | otel.env.hiding | config_opentelemetry:otel_log_level                                            | config_datadog:dd_log_level                 ",
    "propagators      | OTEL_PROPAGATORS                                          | b3                            | DD_TRACE_PROPAGATION_STYLE    | datadog                 | count      | 1           | tracers         | otel.env.hiding | config_opentelemetry:otel_propagators                                          | config_datadog:dd_trace_propagation_style   ",
    "traces sampler   | OTEL_TRACES_SAMPLER                                       | parentbased_always_off        | DD_TRACE_SAMPLE_RATE          | 1.0                     | count      | 1           | tracers         | otel.env.hiding | config_opentelemetry:otel_traces_sampler                                       | config_datadog:dd_trace_sample_rate         ",
    "request headers  | OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS  | My-OtelHeader                 | DD_TRACE_REQUEST_HEADER_TAGS  | My-DDHeader             | count      | 1           | tracers         | otel.env.hiding | config_opentelemetry:otel_instrumentation_http_client_capture_request_headers  | config_datadog:dd_trace_request_header_tags ",
    "response headers | OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_RESPONSE_HEADERS | My-OtelHeader                 | DD_TRACE_RESPONSE_HEADER_TAGS | My-DDHeader             | count      | 1           | tracers         | otel.env.hiding | config_opentelemetry:otel_instrumentation_http_server_capture_response_headers | config_datadog:dd_trace_response_header_tags",
    "extensions       | OTEL_JAVAAGENT_EXTENSIONS                                 | /opt/opentelemetry/extensions | DD_TRACE_EXTENSIONS_PATH      | /opt/datadog/extensions | count      | 1           | tracers         | otel.env.hiding | config_opentelemetry:otel_javaagent_extensions                                 | config_datadog:dd_trace_extensions_path     "
    // Although DD env vars take precedence as expected, no warning/telemetry is outputted when
    // there is conflict between envvars in the below tests
    // as the code to compare these variables is not implemented in the OtelEnvironmentConfigSource
    // @spotless:off
    // trace debug | OTEL_LOG_LEVEL        | info            | DD_TRACE_DEBUG             | true              | count | 1 | tracers | otel.env.hiding | config_opentelemetry:otel_log_level       | config_datadog:dd_trace_debug
    // dd service  | DD_SERVICE            | DD_TEST_SERVICE | OTEL_SERVICE_NAME          | OTEL_TEST_SERVICE | count | 1 | tracers | otel.env.hiding | config_opentelemetry:otel_service_name    | config_datadog:dd_service
    // traces off  | OTEL_TRACES_EXPORTER  | none            | DD_TRACE_ENABLED           | true              | count | 1 | tracers | otel.env.hiding | config_opentelemetry:otel_traces_exporter | config_datadog:dd_trace_enabled
    // metrics off | OTEL_METRICS_EXPORTER | none            | DD_RUNTIME_METRICS_ENABLED | true              | count | 1 | tracers | otel.env.hiding | config_opentelemetry:otel_metrics_exporte | config_datadog:dd_runtime_metrics_enabled
    // @spotless:on
  })
  void hidingMetric(
      String otelEnvKey,
      String otelEnvValue,
      String ddEnvKey,
      String ddEnvValue,
      String metricType,
      int metricValue,
      String metricNamespace,
      String metricName,
      String tagsOtelValue,
      String tagsDdValue) {
    // setup
    injectEnvConfig(otelEnvKey, otelEnvValue, false);
    injectEnvConfig(ddEnvKey, ddEnvValue, false);
    injectEnvConfig("DD_TRACE_OTEL_ENABLED", "true", false);

    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertDuplicatedMetric(
        metrics,
        metricType,
        metricValue,
        metricNamespace,
        metricName,
        asList(tagsOtelValue, tagsDdValue));
  }

  // Not included in data driven test as "=" is not supported in the environment variable name of
  // the Environment map
  @Test
  @WithConfig(
      key = "OTEL_RESOURCE_ATTRIBUTES",
      value = "env=oteltest,version=0.0.1",
      env = true,
      addPrefix = false)
  @WithConfig(key = "DD_TAGS", value = "env=ddtest,version=0.0.2", env = true, addPrefix = false)
  @WithConfig(key = "DD_TRACE_OTEL_ENABLED", value = "true", env = true, addPrefix = false)
  void otelResourceAttributesHidingMetric() {
    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertDuplicatedMetric(
        metrics,
        "count",
        1,
        "tracers",
        "otel.env.hiding",
        asList("config_opentelemetry:otel_resource_attributes", "config_datadog:dd_tags"));
  }

  @TableTest({
    "scenario       | otelEnvKey          | otelEnvValue | metricType | metricValue | metricNamespace | metricName       | tagsOtelValue                            | tagsDdValue                              ",
    "propagators    | OTEL_PROPAGATORS    | StyleUnknown | count      | 1           | tracers         | otel.env.invalid | config_opentelemetry:otel_propagators    | config_datadog:dd_trace_propagation_style",
    "traces sampler | OTEL_TRACES_SAMPLER | newrate      | count      | 1           | tracers         | otel.env.invalid | config_opentelemetry:otel_traces_sampler | config_datadog:dd_trace_sample_rate      "
  })
  void invalidMetric(
      String otelEnvKey,
      String otelEnvValue,
      String metricType,
      int metricValue,
      String metricNamespace,
      String metricName,
      String tagsOtelValue,
      String tagsDdValue) {
    // setup
    injectEnvConfig(otelEnvKey, otelEnvValue, false);
    injectEnvConfig("DD_TRACE_OTEL_ENABLED", "true", false);

    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertDuplicatedMetric(
        metrics,
        metricType,
        metricValue,
        metricNamespace,
        metricName,
        asList(tagsOtelValue, tagsDdValue));
  }

  @TableTest({
    "scenario         | otelEnvKey            | otelEnvValue | metricType | metricValue | metricNamespace | metricName           | tagsOtelValue                             ",
    "metrics exporter | OTEL_METRICS_EXPORTER | zipkin       | count      | 1           | tracers         | otel.env.unsupported | config_opentelemetry:otel_metrics_exporter",
    "traces exporter  | OTEL_TRACES_EXPORTER  | zipkin       | count      | 1           | tracers         | otel.env.unsupported | config_opentelemetry:otel_traces_exporter ",
    "logs exporter    | OTEL_LOGS_EXPORTER    | zipkin       | count      | 1           | tracers         | otel.env.unsupported | config_opentelemetry:otel_logs_exporter   "
  })
  void unsupportedMetric(
      String otelEnvKey,
      String otelEnvValue,
      String metricType,
      int metricValue,
      String metricNamespace,
      String metricName,
      String tagsOtelValue) {
    // setup
    injectEnvConfig(otelEnvKey, otelEnvValue, false);
    injectEnvConfig("DD_TRACE_OTEL_ENABLED", "true", false);

    // when
    List<OtelEnvMetric> metrics = drainMetrics();

    // then
    assertDuplicatedMetric(
        metrics,
        metricType,
        metricValue,
        metricNamespace,
        metricName,
        singletonList(tagsOtelValue));
  }

  private static List<OtelEnvMetric> drainMetrics() {
    OtelEnvMetricCollectorImpl collector = OtelEnvMetricCollectorImpl.getInstance();
    collector.prepareMetrics();
    return new ArrayList<>(collector.drain());
  }

  /** Asserts that the same metric was emitted twice (see the note at the top). */
  private static void assertDuplicatedMetric(
      List<OtelEnvMetric> metrics,
      String metricType,
      int metricValue,
      String metricNamespace,
      String metricName,
      List<String> tags) {
    assertEquals(2, metrics.size());
    assertEquals(metrics.get(0), metrics.get(1));
    OtelEnvMetric metric = metrics.get(0);
    assertEquals(metricType, metric.type);
    assertEquals(metricValue, metric.value.intValue());
    assertEquals(metricNamespace, metric.namespace);
    assertEquals(metricName, metric.metricName);
    assertEquals(tags, metric.tags);
  }
}
