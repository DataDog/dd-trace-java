package com.datadog.iast.telemetry

import com.datadog.iast.IastRequestContext
import datadog.trace.api.Config
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastMetricCollector
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic
import groovy.transform.ToString

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
    context instanceof IastMetricCollector.HasTelemetryCollector

    when:
    final collector = (context as IastMetricCollector.HasTelemetryCollector).telemetryCollector
    collector.addMetric(metric, 1)
    telemetry.onRequestEnded(context, trace)

    then:
    1 * trace.setTagTop(String.format(IastTelemetry.TRACE_METRIC_PATTERN, metric.spanTag), 1L)
    0 * _
  }

  void 'test telemetry: #description'() {
    setup:
    final telemetry = new IastTelemetryImpl(Verbosity.DEBUG)
    final trace = Mock(TraceSegment)
    final context = telemetry.onRequestStarted()
    final collector = (context as IastMetricCollector.HasTelemetryCollector).telemetryCollector
    metrics.each { collector.addMetric(it.metric, it.value) }

    when:
    telemetry.onRequestEnded(context, trace)

    then:
    metrics.findAll { it.metric.scope == Scope.REQUEST }.each {
      1 * trace.setTagTop(String.format(IastTelemetry.TRACE_METRIC_PATTERN, it.metric.spanTag), it.value)
    }
    0 * _

    where:
    metrics                                                            | description
    [
      metric(REQUEST_TAINTED, 123),
      metric(EXECUTED_SOURCE_REQUEST_PARAMETER_VALUE, 2L),
      metric(EXECUTED_SOURCE_REQUEST_HEADER_VALUE, 4L),
      metric(EXECUTED_SINK_SQL_INJECTION, 1L),
      metric(EXECUTED_SINK_COMMAND_INJECTION, 2L),
    ]                                                                  | 'List of only request scoped metrics'
    [
      metric(REQUEST_TAINTED, 123),
      metric(INSTRUMENTED_SOURCE_REQUEST_PARAMETER_VALUE, 2L),
    ]                                                                  | 'Mix between global and request scoped metrics'
  }

  private static Data metric(final IastMetric metric, final long value) {
    return new Data(metric: metric, value: value)
  }

  @ToString
  private static class Data {
    IastMetric metric
    long value
  }
}
