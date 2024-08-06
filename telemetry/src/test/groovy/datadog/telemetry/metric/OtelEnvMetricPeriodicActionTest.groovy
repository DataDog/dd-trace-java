package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import spock.lang.Specification

class OtelEnvMetricPeriodicActionTest extends Specification{

  void 'test otel env var hiding metric'() {
    setup:

    final telemetryService = Mock(TelemetryService)
    final action = new OtelEnvMetricPeriodicAction()


    when:
    action.collector().setHidingOtelEnvVarMetric("otel_service_name","dd_service_name")
    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'otel.env.hiding' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_opentelemetry:otel_service_name', 'config_datadog:dd_service_name'] &&
        metric.type == Metric.TypeEnum.COUNT
    } )

    0 * _._
  }
  void 'test otel env var unsupported metric'() {
    setup:

    final telemetryService = Mock(TelemetryService)
    final action = new OtelEnvMetricPeriodicAction()


    when:
    action.collector().setUnsupportedOtelEnvVarMetric("unsupported_env_var")
    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'otel.env.unsupported' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_opentelemetry:unsupported_env_var'] &&
        metric.type == Metric.TypeEnum.COUNT
    } )

    0 * _._
  }
  void 'test otel env var invalid metric'() {
    setup:

    final telemetryService = Mock(TelemetryService)
    final action = new OtelEnvMetricPeriodicAction()


    when:
    action.collector().setInvalidOtelEnvVarMetric("otel_env_var","dd_env_var")
    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'otel.env.invalid' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_opentelemetry:otel_env_var', 'config_datadog:dd_env_var'] &&
        metric.type == Metric.TypeEnum.COUNT
    } )

    0 * _._
  }

  void 'test Ootel env var multiple metrics'() {
    setup:

    final telemetryService = Mock(TelemetryService)
    final action = new OtelEnvMetricPeriodicAction()


    when:
    action.collector().setInvalidOtelEnvVarMetric("otel_env_var","dd_env_var")
    action.collector().setInvalidOtelEnvVarMetric("otel_env_var2","dd_env_var2")
    action.collector().setHidingOtelEnvVarMetric("otel_service_name","dd_service_name")
    action.collector().setUnsupportedOtelEnvVarMetric("unsupported_env_var")
    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'otel.env.invalid' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_opentelemetry:otel_env_var', 'config_datadog:dd_env_var'] &&
        metric.type == Metric.TypeEnum.COUNT
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'otel.env.invalid' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_opentelemetry:otel_env_var2', 'config_datadog:dd_env_var2'] &&
        metric.type == Metric.TypeEnum.COUNT
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'otel.env.hiding' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_opentelemetry:otel_service_name', 'config_datadog:dd_service_name'] &&
        metric.type == Metric.TypeEnum.COUNT
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'otel.env.unsupported' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_opentelemetry:unsupported_env_var'] &&
        metric.type == Metric.TypeEnum.COUNT
    } )

    0 * _._
  }
}
