package datadog.telemetry.iast

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastTelemetryCollector
import groovy.transform.CompileDynamic
import spock.lang.Specification

@CompileDynamic
class IastTelemetryPeriodicActionTest extends Specification {

  void 'test metric'() {
    given:
    final action = new IastTelemetryPeriodicAction()
    final service = Mock(TelemetryService)
    final iastMetric = IastMetric.EXECUTED_TAINTED
    final value = 23L

    when:
    IastTelemetryCollector.add(iastMetric, value)
    action.doIteration(service)

    then:
    1 * service.addMetric({ matches(it, iastMetric, value, []) })
    0 * _
  }

  void 'test tagged metric'() {
    given:
    final action = new IastTelemetryPeriodicAction()
    final service = Mock(TelemetryService)
    final iastMetric = IastMetric.INSTRUMENTED_SOURCE
    final value = 23L
    final tag = 'my_source'

    when:
    IastTelemetryCollector.add(iastMetric, value, tag)
    action.doIteration(service)

    then:
    1 * service.addMetric({ matches(it, iastMetric, value, ["${iastMetric.tag}:${tag}"]) })
    0 * _
  }

  void 'test with no metrics'() {
    given:
    final action = new IastTelemetryPeriodicAction()
    final service = Mock(TelemetryService)

    when:
    action.doIteration(service)

    then:
    0 * _
  }

  private static boolean matches(final Metric metric, final IastMetric iastMetric, final long value, final List<String> tags) {
    if (metric.namespace != IastTelemetryPeriodicAction.NAMESPACE) {
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
