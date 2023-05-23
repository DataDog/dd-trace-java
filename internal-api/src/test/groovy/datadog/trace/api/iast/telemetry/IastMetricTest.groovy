package datadog.trace.api.iast.telemetry

import datadog.trace.api.Config
import spock.lang.Specification


class IastMetricTest extends Specification {

  void 'test iast metrics attributes'() {
    given:
    final verbosity = Config.get().getIastTelemetryVerbosity()

    when:
    final name = metric.name

    then:
    name != null

    when:
    final tag = metric.tag

    then:
    tag == metric.tagName == null ? null : "${metric.tagName}:${metric.tagValue}"

    when:
    final spanTag = metric.spanTag

    then:
    spanTag == metric.tagName == null ? null : "${metric.tagName}.${metric.tagValue}".toLowerCase().replaceAll('\\_', '.')

    when:
    final enabled = metric.isEnabled(verbosity)

    then:
    enabled == verbosity.isEnabled(metric.verbosity)

    where:
    metric << (IastMetric.values() as List<IastMetric>)
  }
}
