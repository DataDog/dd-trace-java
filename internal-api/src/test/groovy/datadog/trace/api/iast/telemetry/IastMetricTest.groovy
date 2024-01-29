package datadog.trace.api.iast.telemetry

import datadog.trace.api.Config
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityTypes
import spock.lang.Specification

import static datadog.trace.api.iast.telemetry.IastMetric.TRACE_METRIC_PREFIX

class IastMetricTest extends Specification {

  void 'test iast metrics attributes'() {
    given:
    final verbosity = Config.get().getIastTelemetryVerbosity()

    when:
    final name = metric.name

    then:
    name != null

    when:
    final enabled = metric.isEnabled(verbosity)

    then:
    enabled == verbosity.isEnabled(metric.verbosity)

    where:
    metric << (IastMetric.values() as List<IastMetric>)
  }

  void 'test unwrapping of tags'() {
    when:
    final result = metricTag.unwrap(tag)

    then:
    result == expected

    where:
    metricTag                         | tag                                 | expected
    IastMetric.Tag.VULNERABILITY_TYPE | VulnerabilityTypes.RESPONSE_HEADER  | VulnerabilityTypes.RESPONSE_HEADER_TYPES
    IastMetric.Tag.VULNERABILITY_TYPE | VulnerabilityTypes.SPRING_RESPONSE  | VulnerabilityTypes.SPRING_RESPONSE_TYPES
    IastMetric.Tag.VULNERABILITY_TYPE | VulnerabilityTypes.SQL_INJECTION    | null
    IastMetric.Tag.SOURCE_TYPE        | SourceTypes.REQUEST_PARAMETER_VALUE | null
  }

  void 'test metric tags'() {
    when:
    final telemetryTags = []
    final spanTags = []
    if (tag == null) {
      spanTags.add(metric.getSpanTag((byte) -1))
    } else {
      tag.values.eachWithIndex { value, index ->
        telemetryTags.add(metric.getTelemetryTag((byte) index))
        spanTags.add(metric.getSpanTag((byte) index))
      }
    }

    then:
    if (tag == null) {
      telemetryTags.empty
      spanTags == [TRACE_METRIC_PREFIX + metric.name]
    } else {
      tag.values.each {
        assert telemetryTags.contains(tag.name + ':' + it)
        assert spanTags.contains(TRACE_METRIC_PREFIX + metric.name + '.' + it.toLowerCase().replace((char) '.', (char) '_'))
      }
    }


    where:
    metric                         | tag
    IastMetric.INSTRUMENTED_SINK   | IastMetric.Tag.VULNERABILITY_TYPE
    IastMetric.INSTRUMENTED_SOURCE | IastMetric.Tag.SOURCE_TYPE
    IastMetric.EXECUTED_TAINTED    | null
  }
}
