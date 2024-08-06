package datadog.trace.api.telemetry

//import datadog.trace.bootstrap.config.provider.OtelEnvironmentConfigSource
import datadog.trace.test.util.DDSpecification

//import static datadog.trace.api.config.GeneralConfig.LOG_LEVEL
//import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME
//import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED

// PLEASE READ
// When a metric is generated, it's duplicated in these tests. We call twice setupOteEnvironment() because of the separation of the configuration done in the rebuild function of DDSpecification between datadog/trace/api/InstrumenterConfig.java and internal-api/src/main/java/datadog/trace/api/Config.java.
// We are calling ConfigProvider.createDefault()) twice


public class OtelEnvMetricCollectorTest extends DDSpecification {

  def "otel disabled - no metric"() {
    setup:
    injectEnvConfig('DD_SERVICE_NAME', 'DD_TEST_SERVICE', false)
    injectEnvConfig('OTEL_SERVICE_NAME', 'OTEL_TEST_SERVICE', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'false', false)

    def collector = OtelEnvMetricCollector.getInstance()

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

    def collector = OtelEnvMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    def hidingMetric = metrics[0]
    hidingMetric.type == 'count'
    hidingMetric.value == 1
    hidingMetric.namespace == 'tracers'
    hidingMetric.metricName == 'otel.env.hiding'
    hidingMetric.tags.size() == 2
    hidingMetric.tags[0] == 'config_opentelemetry:otel_sdk_disabled'
    hidingMetric.tags[1] == 'config_datadog:dd_trace_otel_enabled'
  }

  def "otel_service_name - hiding"() {
    setup:
    injectEnvConfig('DD_SERVICE_NAME', 'DD_TEST_SERVICE', false)
    injectEnvConfig('OTEL_SERVICE_NAME', 'OTEL_TEST_SERVICE', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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
    hidingMetric.tags[0] == 'config_opentelemetry:otel_service_name'
    hidingMetric.tags[1] == 'config_datadog:dd_service_name'
  }

  def "otel_log_level - hiding"() {
    setup:
    injectEnvConfig('OTEL_LOG_LEVEL', 'debug', false)
    injectEnvConfig('DD_LOG_LEVEL', 'info', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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
    hidingMetric.tags[0] == 'config_opentelemetry:otel_log_level'
    hidingMetric.tags[1] == 'config_datadog:dd_log_level'
  }



  def "otel_service_name - hiding  + otel_propagators - unsupported"() {
    setup:
    injectEnvConfig('DD_SERVICE_NAME', 'DD_TEST_SERVICE', false)
    injectEnvConfig('OTEL_SERVICE_NAME', 'OTEL_TEST_SERVICE', false)
    injectEnvConfig('OTEL_PROPAGATORS', 'MyStyle', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 4
  }


  def "otel_propagators - hiding"() {
    setup:
    injectEnvConfig('OTEL_PROPAGATORS', 'b3', false)
    injectEnvConfig('DD_TRACE_PROPAGATION_STYLE', 'datadog', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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
    hidingMetric.tags[0] == 'config_opentelemetry:otel_propagators'
    hidingMetric.tags[1] == 'config_datadog:dd_trace_propagation_style'
  }

  def "otel_propagators - invalid"() {
    setup:
    injectEnvConfig('OTEL_PROPAGATORS', 'StyleUnknown', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def invalidMetric = metrics[0]
    invalidMetric.type == 'count'
    invalidMetric.value == 1
    invalidMetric.namespace == 'tracers'
    invalidMetric.metricName == 'otel.env.invalid'
    invalidMetric.tags.size() == 2
    invalidMetric.tags[0] == 'config_opentelemetry:otel_propagators'
    invalidMetric.tags[1] == 'config_datadog:dd_trace_propagation_style'
  }

  def "otel_traces_sampler - hiding"() {
    setup:
    injectEnvConfig('OTEL_TRACES_SAMPLER', 'parentbased_always_off', false)
    injectEnvConfig('DD_TRACE_SAMPLE_RATE', '1.0', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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
    hidingMetric.tags[0] == 'config_opentelemetry:otel_traces_sampler'
    hidingMetric.tags[1] == 'config_datadog:dd_trace_sample_rate'
  }

  def "otel_traces_sampler - invalid"() {
    setup:
    injectEnvConfig('OTEL_TRACES_SAMPLER', 'newrate', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def invalidMetric = metrics[0]
    invalidMetric.type == 'count'
    invalidMetric.value == 1
    invalidMetric.namespace == 'tracers'
    invalidMetric.metricName == 'otel.env.invalid'
    invalidMetric.tags.size() == 2
    invalidMetric.tags[0] == 'config_opentelemetry:otel_traces_sampler'
    invalidMetric.tags[1] == 'config_datadog:dd_trace_sample_rate'
  }


  def "otel_metrics_exporter - invalid"() {
    setup:
    injectEnvConfig('OTEL_METRICS_EXPORTER', 'otlp', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def invalidMetric = metrics[0]
    invalidMetric.type == 'count'
    invalidMetric.value == 1
    invalidMetric.namespace == 'tracers'
    invalidMetric.metricName == 'otel.env.invalid'
    invalidMetric.tags.size() == 2
    invalidMetric.tags[0] == 'config_opentelemetry:otel_metrics_exporter'
    invalidMetric.tags[1] == 'config_datadog:dd_runtime_metrics_enabled'
  }




  def "otel_logs_exporter - unsupported "() {
    setup:
    injectEnvConfig('OTEL_LOGS_EXPORTER', 'otlp', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def unsupportedMetric = metrics[0]
    unsupportedMetric.type == 'count'
    unsupportedMetric.value == 1
    unsupportedMetric.namespace == 'tracers'
    unsupportedMetric.metricName == 'otel.env.unsupported'
    unsupportedMetric.tags.size() == 1
    unsupportedMetric.tags[0] == 'config_opentelemetry:otel_logs_exporter'
  }



  def "otel_traces_exporter - invalid"() {
    setup:
    injectEnvConfig('OTEL_TRACES_EXPORTER', 'otlp', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics[0] == metrics[1]
    def invalidMetric = metrics[0]
    invalidMetric.type == 'count'
    invalidMetric.value == 1
    invalidMetric.namespace == 'tracers'
    invalidMetric.metricName == 'otel.env.invalid'
    invalidMetric.tags.size() == 2
    invalidMetric.tags[0] == 'config_opentelemetry:otel_traces_exporter'
    invalidMetric.tags[1] == 'config_datadog:dd_trace_enabled'
  }

  def "otel_resource_attributes - hiding"() {
    setup:
    injectEnvConfig('OTEL_RESOURCE_ATTRIBUTES', 'env=oteltest,version=0.0.1', false)
    injectEnvConfig('DD_TAGS', 'env=ddtest,version=0.0.2', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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


  def "otel_instrumentation_http_client_capture-request-headers - hiding "() {
    setup:
    injectEnvConfig('OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS', 'My-OtelHeader', false)
    injectEnvConfig('DD_TRACE_REQUEST_HEADER_TAGS', 'My-DDHeader', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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
    hidingMetric.tags[0] == 'config_opentelemetry:otel_instrumentation_http_client_capture-request-headers'
    hidingMetric.tags[1] == 'config_datadog:dd_trace_request_header_tags'
  }
  def "otel_instrumentation_http_server_capture-response-headers -  hiding "() {
    setup:
    injectEnvConfig('OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_RESPONSE_HEADERS', 'My-OtelHeader', false)
    injectEnvConfig('DD_TRACE_RESPONSE_HEADER_TAGS', 'My-DDHeader', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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
    hidingMetric.tags[0] == 'config_opentelemetry:otel_instrumentation_http_server_capture-response-headers'
    hidingMetric.tags[1] == 'config_datadog:dd_trace_response_header_tags'
  }

  def "otel_javaagent_extensions - hiding "() {
    setup:
    injectEnvConfig('OTEL_JAVAAGENT_EXTENSIONS', '/opt/opentelemetry/extensions', false)
    injectEnvConfig('DD_TRACE_EXTENSIONS_PATH', '/opt/datadog/extensions', false)
    injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)

    def collector = OtelEnvMetricCollector.getInstance()

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
    hidingMetric.tags[0] == 'config_opentelemetry:otel_javaagent_extensions'
    hidingMetric.tags[1] == 'config_datadog:dd_trace_extensions_path'
  }

  /// The following tests don't work as the mapping isn't done

  /* def "otel_log_level - hiding - DD_TRACE_DEBUG "() {
   setup:
   injectEnvConfig('OTEL_LOG_LEVEL', 'info', false)
   injectEnvConfig('DD_TRACE_DEBUG', 'true', false)
   injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
   def collector = OtelEnvMetricCollector.getInstance()
   when:
   def source = new OtelEnvironmentConfigSource()
   collector.prepareMetrics()
   def metrics = collector.drain()
   then:
   source.get(LOG_LEVEL) == null
   metrics.size() == 2
   metrics[0] == metrics[1]
   }
   */
  /*def "otel_service_name - hiding - dd_service"() {
   setup:
   injectEnvConfig('DD_SERVICE', 'DD_TEST_SERVICE', false)
   injectEnvConfig('OTEL_SERVICE_NAME', 'OTEL_TEST_SERVICE', false)
   injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
   def collector = OtelEnvMetricCollector.getInstance()
   when:
   collector.prepareMetrics()
   def metrics = collector.drain()
   def source = new OtelEnvironmentConfigSource()
   then:
   source.get(SERVICE_NAME) == null
   metrics.size() == 2
   metrics[0] == metrics[1]
   }
   */

  /*def "otel_traces_exporter - hiding"() {
   setup:
   injectEnvConfig('OTEL_TRACES_EXPORTER', 'none', false)
   injectEnvConfig('DD_TRACE_ENABLED', 'true', false)
   injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
   def collector = OtelEnvMetricCollector.getInstance()
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
   hidingMetric.tags[0] == 'config_opentelemetry:otel_traces_exporter'
   hidingMetric.tags[1] == 'config_datadog:dd_trace_enabled'
   }*/

  /*def "otel_metrics_exporter - hiding" () {
   setup:
   injectEnvConfig('OTEL_METRICS_EXPORTER', 'none', false)
   injectEnvConfig('DD_RUNTIME_METRICS_ENABLED', 'true', false)
   injectEnvConfig('DD_TRACE_OTEL_ENABLED', 'true', false)
   def collector = OtelEnvMetricCollector.getInstance()
   when:
   collector.prepareMetrics()
   def metrics = collector.drain()
   def source = new OtelEnvironmentConfigSource()
   then:
   // RUNTIME_METRICS_ENABLED should be null
   source.get(RUNTIME_METRICS_ENABLED) == null
   metrics.size() == 2
   metrics[0] == metrics[1]
   def hidingMetric = metrics[0]
   hidingMetric.type == 'count'
   hidingMetric.value == 1
   hidingMetric.namespace == 'tracers'
   hidingMetric.metricName == 'otel.env.hiding'
   hidingMetric.tags.size() == 1
   hidingMetric.tags[0] == 'config_opentelemetry:otel_metrics_exporter'
   hidingMetric.tags[1] == 'config_datadog:dd_runtime_metrics_enabled'
   }
   */


}
