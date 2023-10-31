package com.datadog.iast.telemetry


import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityTypes
import datadog.trace.api.iast.telemetry.IastMetric
import groovy.transform.CompileDynamic
import groovy.transform.ToString

import static com.datadog.iast.telemetry.TelemetryRequestEndedHandler.TRACE_METRIC_PATTERN
import static datadog.trace.api.iast.telemetry.IastMetric.*

@CompileDynamic
class TelemetryRequestEndedHandlerTest extends AbstractTelemetryCallbackTest {

  void 'test telemetry with request scoped metric'() {
    given:
    final handler = new TelemetryRequestEndedHandler(delegate)
    final metric = EXECUTED_TAINTED

    when:
    iastCtx.metricCollector.addMetric(metric, (byte) -1, 1)
    handler.apply(reqCtx, span)

    then:
    1 * delegate.apply(reqCtx, span)
    1 * traceSegment.setTagTop(String.format(TRACE_METRIC_PATTERN, getSpanTagValue(metric)), 1)

    when:
    globalCollector.prepareMetrics()
    final drained = globalCollector.drain()

    then:
    drained.size() == 1
    drained[0].metric == metric
    drained[0].type == 'count'
    drained[0].value.longValue() == 1
  }

  void 'test telemetry: #description'() {
    setup:
    final handler = new TelemetryRequestEndedHandler(delegate)
    final collector = iastCtx.metricCollector
    metrics.each { collector.addMetric(it.metric, it.tagValue, it.value) }

    when:
    handler.apply(reqCtx, span)

    then: 'request scoped metrics are propagated to the span'
    1 * delegate.apply(reqCtx, span)
    metrics.findAll { it.metric.scope == Scope.REQUEST }.each {
      1 * traceSegment.setTagTop(String.format(TRACE_METRIC_PATTERN, getSpanTagValue(it.metric, it.tagValue)), it.value)
    }

    when:
    globalCollector.prepareMetrics()
    final drained = globalCollector.drain()

    then: 'all metrics are propagated to the global collector'
    metrics.every {
      drained.find { d -> d.metric == it.metric && d.tagValue == it.tagValue }?.value.longValue() == it.value
    }

    where:
    metrics                                                                | description
    [
      metric(EXECUTED_TAINTED, 123),
      metric(EXECUTED_SOURCE, SourceTypes.REQUEST_PARAMETER_VALUE, 2),
      metric(EXECUTED_SOURCE, SourceTypes.REQUEST_HEADER_VALUE, 4),
      metric(EXECUTED_SINK, VulnerabilityTypes.SQL_INJECTION, 1),
      metric(EXECUTED_SINK, VulnerabilityTypes.COMMAND_INJECTION, 2),
    ]                                                                      | 'List of only request scoped metrics'
    [
      metric(EXECUTED_TAINTED, 123),
      metric(INSTRUMENTED_SOURCE, SourceTypes.REQUEST_PARAMETER_VALUE, 2),
    ]                                                                      | 'Mix between global and request scoped metrics'
  }

  private static String getSpanTagValue(final IastMetric metric, final Byte tagValue = null) {
    return metric.getTag() == null
      ? metric.getName()
      : String.format("%s.%s", metric.getName(), metric.tag.toString(tagValue).toLowerCase().replaceAll("\\.", "_"))
  }

  private static Data metric(final IastMetric metric, final byte tagValue, final int value) {
    return new Data(metric: metric, tagValue: tagValue, value: value)
  }

  private static Data metric(final IastMetric metric, final int value) {
    return new Data(metric: metric, tagValue: (byte) -1, value: value)
  }

  @ToString
  private static class Data {
    IastMetric metric
    byte tagValue
    int value
  }
}
