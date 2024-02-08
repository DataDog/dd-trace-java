package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastMetricCollector
import groovy.transform.CompileDynamic
import spock.lang.Specification

@CompileDynamic
class IastMetricPeriodicActionTest extends Specification {

  void 'test metric'() {
    given:
    final action = new IastMetricPeriodicAction()
    final service = Mock(TelemetryService)
    final iastMetric = IastMetric.EXECUTED_TAINTED
    final value = 23

    when:
    IastMetricCollector.add(iastMetric, value)
    IastMetricCollector.get().prepareMetrics()
    action.doIteration(service)

    then:
    1 * service.addMetric({ matches(it, iastMetric, value, []) })
    0 * _
  }

  void 'test tagged metric'() {
    given:
    final action = new IastMetricPeriodicAction()
    final service = Mock(TelemetryService)
    final iastMetric = IastMetric.INSTRUMENTED_SOURCE
    final tag = SourceTypes.REQUEST_PARAMETER_VALUE
    final tagString = SourceTypes.toString(tag)
    final value = 23

    when:
    IastMetricCollector.add(iastMetric, tag, value)
    IastMetricCollector.get().prepareMetrics()
    action.doIteration(service)

    then:
    1 * service.addMetric({ matches(it, iastMetric, value, ["${iastMetric.tag.name}:${tagString}"]) })
    0 * _
  }

  void 'test with no metrics'() {
    given:
    final action = new IastMetricPeriodicAction()
    final service = Mock(TelemetryService)

    when:
    action.doIteration(service)

    then:
    0 * _
  }

  private static boolean matches(final Metric metric, final IastMetric iastMetric, final long value, final List<String> tags) {
    if (metric.namespace != 'iast') {
      return false
    }
    if (metric.metric != iastMetric.name) {
      return false
    }
    if (metric.tags != tags) {
      return false
    }
    return metric.points.first()[1] == value
  }
}
