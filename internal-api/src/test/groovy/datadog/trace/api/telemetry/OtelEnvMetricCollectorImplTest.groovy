package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

// PLEASE READ
// When a metric is generated, it's duplicated in these tests. We call twice setupOteEnvironment() because of the separation of the configuration done in the rebuild function of DDSpecification between datadog/trace/api/InstrumenterConfig.java and internal-api/src/main/java/datadog/trace/api/Config.java.
// We are calling ConfigProvider.createDefault()) twice
// We check otel configuration keys only if opentelemetry is enabled using DD_TRACE_OTEL_ENABLED=true or OTEL_SDK_DISABLED=false.
// Note that if DD_TRACE_OTEL_ENABLED is set, its value will overwrite the one in OTEL_SDK_DISABLED.


class OtelEnvMetricCollectorImplTest extends DDSpecification {

  def "otel disabled - no metric"() {
    setup:
    injectEnvConfig('DD_SERVICE_NAME', 'DD_TEST_SERVICE', false)
    injectEnvConfig('OTEL_SERVICE_NAME', 'OTEL_TEST_SERVICE', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'false', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 0
  }

  def "otel_sdk_disabled - hiding"() {
    setup:
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
    injectEnvConfig('OTEL_SDK_DISABLED', 'true', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def hidingMetric = metrics[0]
    hidingMetric.type == 'count'
    hidingMetric.value == 1
    hidingMetric.namespace == 'tracers'
    hidingMetric.metricName == 'otel.env.hiding'
    hidingMetric.tags.size() == 2
    hidingMetric.tags[0] == 'config_opentelemetry:otel_sdk_disabled'
    hidingMetric.tags[1] == 'config_datadog:dd_trace_otel_enabled'
  }

  def "otel_service_name only - no metric"() {
    setup:
    injectEnvConfig('OTEL_SERVICE_NAME', 'OTEL_TEST_SERVICE', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 0
  }

  def "multiple metrics"() {
    setup:
    injectEnvConfig('DD_SERVICE_NAME', 'DD_TEST_SERVICE', false)
    injectEnvConfig('OTEL_SERVICE_NAME', 'OTEL_TEST_SERVICE', false)
    injectEnvConfig('OTEL_PROPAGATORS', 'MyStyle', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 4
  }



  def "hiding metric"() {
    setup:
    injectEnvConfig(otelEnvKey, otelEnvValue, false)
    injectEnvConfig(ddEnvKey, ddEnvValue, false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def hidingMetric = metrics[0]
    hidingMetric.type == metricType
    hidingMetric.value == metricValue
    hidingMetric.namespace == metricNamespace
    hidingMetric.metricName == metricName
    hidingMetric.tags.size() == 2
    hidingMetric.tags[0] == tagsOtelValue
    hidingMetric.tags[1] == tagsDdValue

    where:
    otelEnvKey                                                    | otelEnvValue                    | ddEnvKey                        | ddEnvValue                ||  metricType  | metricValue | metricNamespace | metricName        | tagsOtelValue                                                                    | tagsDdValue
    'DD_SERVICE_NAME'                                             | 'DD_TEST_SERVICE'               | 'OTEL_SERVICE_NAME'             | 'OTEL_TEST_SERVICE'       || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_service_name'                                          | 'config_datadog:dd_service_name'
    'OTEL_LOG_LEVEL'                                              | 'debug'                         | 'DD_LOG_LEVEL'                  | 'INFO'                    || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_log_level'                                             | 'config_datadog:dd_log_level'
    'OTEL_PROPAGATORS'                                            | 'b3'                            | 'DD_TRACE_PROPAGATION_STYLE'    | 'datadog'                 || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_propagators'                                           | 'config_datadog:dd_trace_propagation_style'
    'OTEL_TRACES_SAMPLER'                                         | 'parentbased_always_off'        | 'DD_TRACE_SAMPLE_RATE'          | '1.0'                     || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_traces_sampler'                                        | 'config_datadog:dd_trace_sample_rate'
    'OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS'    | 'My-OtelHeader'                 | 'DD_TRACE_REQUEST_HEADER_TAGS'  | 'My-DDHeader'             || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_instrumentation_http_client_capture_request_headers'   | 'config_datadog:dd_trace_request_header_tags'
    'OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_RESPONSE_HEADERS'   | 'My-OtelHeader'                 | 'DD_TRACE_RESPONSE_HEADER_TAGS' | 'My-DDHeader'             || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_instrumentation_http_server_capture_response_headers'  | 'config_datadog:dd_trace_response_header_tags'
    'OTEL_JAVAAGENT_EXTENSIONS'                                   | '/opt/opentelemetry/extensions' | 'DD_TRACE_EXTENSIONS_PATH'      |'/opt/datadog/extensions'  || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_javaagent_extensions'                                  | 'config_datadog:dd_trace_extensions_path'

    // Although DD env vars take precedence as expected, no warning/telemetry is outputted when there is conflict between envvars in the below tests
    // as the code to compare these variables is not implemented in the OtelEnvironmentConfigSource
    //'OTEL_LOG_LEVEL'                                            | 'info'                          | 'DD_TRACE_DEBUG'                | 'true'                    || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_log_level'                                             | 'config_datadog:dd_trace_debug'
    //'DD_SERVICE'                                                | 'DD_TEST_SERVICE'               | 'OTEL_SERVICE_NAME'             | 'OTEL_TEST_SERVICE'       || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_service_name'                                          | 'config_datadog:dd_service'
    //'OTEL_TRACES_EXPORTER'                                      | 'none'                          | 'DD_TRACE_ENABLED'              | 'true'                    || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_traces_exporter'                                       | 'config_datadog:dd_trace_enabled'
    //'OTEL_METRICS_EXPORTER'                                     | 'none'                          | 'DD_RUNTIME_METRICS_ENABLED'    | 'true'                    || 'count'      | 1           | 'tracers'       | 'otel.env.hiding' |'config_opentelemetry:otel_metrics_exporter'                                      | 'config_datadog:dd_runtime_metrics_enabled'
  }

  //Not included in data driven test as "=" is not supported in the environment variable name of the Environment map
  def "otel_resource_attributes - hiding metric"() {
    setup:
    injectEnvConfig('OTEL_RESOURCE_ATTRIBUTES', 'env=oteltest,version=0.0.1', false)
    injectEnvConfig('DD_TAGS', 'env=ddtest,version=0.0.2', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def hidingMetric = metrics[0]
    hidingMetric.type == 'count'
    hidingMetric.value == 1
    hidingMetric.namespace == 'tracers'
    hidingMetric.metricName == 'otel.env.hiding'
    hidingMetric.tags.size() == 2
    hidingMetric.tags[0] == 'config_opentelemetry:otel_resource_attributes'
    hidingMetric.tags[1] == 'config_datadog:dd_tags'
  }

  def "invalid metric"() {
    setup:
    injectEnvConfig(otelEnvKey, otelEnvValue, false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def invalidMetric = metrics[0]
    invalidMetric.type == metricType
    invalidMetric.value == metricValue
    invalidMetric.namespace == metricNamespace
    invalidMetric.metricName == metricName
    invalidMetric.tags.size() == 2
    invalidMetric.tags[0] == tagsOtelValue
    invalidMetric.tags[1] == tagsDdValue

    where:
    otelEnvKey            | otelEnvValue    ||  metricType | metricValue | metricNamespace | metricName         | tagsOtelValue                              | tagsDdValue
    'OTEL_PROPAGATORS'    | 'StyleUnknown'  || 'count'     | 1           | 'tracers'       | 'otel.env.invalid' | 'config_opentelemetry:otel_propagators'    | 'config_datadog:dd_trace_propagation_style'
    'OTEL_TRACES_SAMPLER' | 'newrate'       || 'count'     | 1           | 'tracers'       | 'otel.env.invalid' | 'config_opentelemetry:otel_traces_sampler' | 'config_datadog:dd_trace_sample_rate'
  }

  def "unsupported metric"() {
    setup:
    injectEnvConfig(otelEnvKey, otelEnvValue, false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
    def collector = OtelEnvMetricCollectorImpl.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def unsupportedMetric = metrics[0]
    unsupportedMetric.type == metricType
    unsupportedMetric.value == metricValue
    unsupportedMetric.namespace == metricNamespace
    unsupportedMetric.metricName == metricName
    unsupportedMetric.tags.size() == 1
    unsupportedMetric.tags[0] == tagsOtelValue

    where:
    otelEnvKey              | otelEnvValue    ||  metricType  | metricValue  | metricNamespace  | metricName             | tagsOtelValue
    'OTEL_METRICS_EXPORTER' | 'otlp'          || 'count'      | 1            | 'tracers'        | 'otel.env.unsupported' | 'config_opentelemetry:otel_metrics_exporter'
    'OTEL_TRACES_EXPORTER'  | 'otlp'          || 'count'      | 1            | 'tracers'        | 'otel.env.unsupported' | 'config_opentelemetry:otel_traces_exporter'
    'OTEL_LOGS_EXPORTER'    | 'otlp'          || 'count'      | 1            | 'tracers'        | 'otel.env.unsupported' | 'config_opentelemetry:otel_logs_exporter'
  }

}
