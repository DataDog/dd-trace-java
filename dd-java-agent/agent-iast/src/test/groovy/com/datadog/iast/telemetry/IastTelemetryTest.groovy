package com.datadog.iast.telemetry

import com.datadog.iast.IastRequestContext
import datadog.trace.api.Config
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastTelemetryCollector
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic
import groovy.transform.ToString

import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_VALUE_STRING
import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_VALUE_STRING
import static datadog.trace.api.iast.VulnerabilityTypes.COMMAND_INJECTION
import static datadog.trace.api.iast.VulnerabilityTypes.SQL_INJECTION
import static datadog.trace.api.iast.telemetry.IastMetric.*

@CompileDynamic
class IastTelemetryTest extends DDSpecification {

  void 'test builder'() {
    given:
    final config = Mock(Config) {
      isTelemetryEnabled() >> { verbosity != null }
      getIastTelemetryVerbosity() >> verbosity
    }

    when:
    final telemetry = IastTelemetry.build(config)

    then:
    instance.isInstance(telemetry)

    where:
    verbosity             | instance
    null                  | NoOpTelemetry
    Verbosity.OFF         | NoOpTelemetry
    Verbosity.MANDATORY   | IastTelemetryImpl
    Verbosity.INFORMATION | IastTelemetryImpl
    Verbosity.DEBUG       | IastTelemetryImpl
  }

  void 'test no op telemetry'() {
    given:
    final telemetry = new NoOpTelemetry()
    final trace = Mock(TraceSegment)

    when:
    final context = telemetry.onRequestStarted()

    then:
    context instanceof IastRequestContext

    when:
    telemetry.onRequestEnded(context, trace)

    then:
    0 * _
  }

  void 'test telemetry with request scoped metric'() {
    given:
    final telemetry = new IastTelemetryImpl(Verbosity.DEBUG)
    final trace = Mock(TraceSegment)
    final metric = TAINTED_FLAT_MODE

    when:
    final context = telemetry.onRequestStarted()

    then:
    context instanceof IastTelemetryCollector.HasTelemetryCollector

    when:
    final collector = (context as IastTelemetryCollector.HasTelemetryCollector).telemetryCollector
    collector.addMetric(metric, 1, null)
    telemetry.onRequestEnded(context, trace)

    then:
    1 * trace.setTagTop(String.format(IastTelemetry.TRACE_METRIC_PATTERN, metric.name), 1L)
    0 * _
  }

  void 'test telemetry: #description'() {
    setup:
    final telemetry = new IastTelemetryImpl(Verbosity.DEBUG)
    final trace = Mock(TraceSegment)
    final context = telemetry.onRequestStarted()
    final collector = (context as IastTelemetryCollector.HasTelemetryCollector).telemetryCollector
    metrics.each { collector.addMetric(it.metric, it.value, it.tag) }

    when:
    telemetry.onRequestEnded(context, trace)

    then:
    metrics.findAll { it.metric.scope == Scope.REQUEST }.each {
      final name = "${it.metric.name}${it.tag ? '.' + it.tag.toLowerCase().replaceAll('\\.', '_') : ''}"
      1 * trace.setTagTop(String.format(IastTelemetry.TRACE_METRIC_PATTERN, name), it.value)
    }
    0 * _

    where:
    metrics                                                            | description
    [
      metric(REQUEST_TAINTED, 123),
      metric(EXECUTED_SOURCE, 2L, REQUEST_PARAMETER_VALUE_STRING),
      metric(EXECUTED_SOURCE, 4L, REQUEST_HEADER_VALUE_STRING),
      metric(EXECUTED_SINK, 1L, SQL_INJECTION),
      metric(EXECUTED_SINK, 2L, COMMAND_INJECTION),
    ]                                                                  | 'List of only request scoped metrics'
    [
      metric(REQUEST_TAINTED, 123),
      metric(INSTRUMENTED_SOURCE, 2L, REQUEST_PARAMETER_VALUE_STRING),
    ]                                                                  | 'Mix between global and request scoped metrics'
  }

  private static Data metric(final IastMetric metric, final long value, final String tag = null) {
    return new Data(metric: metric, value: value, tag: tag)
  }

  @ToString
  private static class Data {
    IastMetric metric
    long value
    String tag
  }
}
