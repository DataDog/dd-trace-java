package datadog.trace.api.iast.telemetry

import datadog.trace.api.Config
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityTypes
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
    final enabled = metric.isEnabled(verbosity)

    then:
    enabled == verbosity.isEnabled(metric.verbosity)

    where:
    metric << (IastMetric.values() as List<IastMetric>)
  }

  void 'test parsing of tags'() {
    when:
    final result = metricTag.unwrap(tag)

    then:
    result == expected

    where:
    metricTag                         | tag                                 | expected
    IastMetric.Tag.VULNERABILITY_TYPE | VulnerabilityTypes.RESPONSE_HEADER  | VulnerabilityTypes.RESPONSE_HEADER_TYPES
    IastMetric.Tag.VULNERABILITY_TYPE | VulnerabilityTypes.SQL_INJECTION    | new byte[0]
    IastMetric.Tag.SOURCE_TYPE        | SourceTypes.REQUEST_PARAMETER_VALUE | new byte[0]
  }
}
