package com.datadog.iast.telemetry

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Source
import com.datadog.iast.taint.Ranges
import com.datadog.iast.telemetry.taint.TaintedObjectsWithTelemetry
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityTypes
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastMetricCollector
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.api.internal.TraceSegment
import groovy.transform.ToString
import spock.lang.Shared

import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_SINK
import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_SOURCE
import static datadog.trace.api.iast.telemetry.IastMetric.EXECUTED_TAINTED
import static datadog.trace.api.iast.telemetry.IastMetric.INSTRUMENTED_SOURCE
import static datadog.trace.api.iast.telemetry.IastMetric.REQUEST_TAINTED
import static datadog.trace.api.iast.telemetry.IastMetric.Scope
import static datadog.trace.api.iast.telemetry.IastMetric.TRACE_METRIC_PREFIX

class TelemetryRequestEndedHandlerTest extends IastModuleImplTestBase {

  @Shared
  final IastMetricCollector defaultCollector = IastMetricCollector.get()

  protected RequestEndedHandler delegate
  protected IastMetricCollector globalCollector

  @Override
  void cleanupSpec() {
    IastMetricCollector.register(defaultCollector)
  }

  void setup() {
    InstrumentationBridge.clearIastModules()
    delegate = Spy(new RequestEndedHandler(dependencies))
    globalCollector = new IastMetricCollector()
    IastMetricCollector.register(globalCollector)
    ctx.taintedObjects = TaintedObjectsWithTelemetry.build(Verbosity.DEBUG, ctx)
    ctx.collector = new IastMetricCollector()
  }

  @Override
  protected TraceSegment buildTraceSegment() {
    return Mock(TraceSegment)
  }

  void 'request ends propagates tainted map metrics'() {
    given:
    final handler = new TelemetryRequestEndedHandler(delegate)
    final toTaint = 'hello'
    final source = new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value')
    ctx.taintedObjects.taint(toTaint, Ranges.forCharSequence(toTaint, source))

    when:
    handler.apply(reqCtx, span)

    then:
    1 * delegate.apply(reqCtx, span)
    1 * traceSegment.setTagTop('_dd.iast.telemetry.request.tainted', 1)

    when:
    globalCollector.prepareMetrics()
    final drained = globalCollector.drain()

    then:
    drained.find { it.metric == REQUEST_TAINTED } != null
    drained.find { it.metric == EXECUTED_TAINTED } != null
  }

  void 'test telemetry with request scoped metric'() {
    given:
    final handler = new TelemetryRequestEndedHandler(delegate)
    final metric = REQUEST_TAINTED

    when:
    ctx.metricCollector.addMetric(metric, (byte) -1, 1)
    handler.apply(reqCtx, span)

    then:
    1 * delegate.apply(reqCtx, span)
    1 * traceSegment.setTagTop(TRACE_METRIC_PREFIX + getSpanTagValue(metric), 1)

    when:
    globalCollector.prepareMetrics()
    final drained = globalCollector.drain()

    then:
    drained.size() == 1
    drained[0].metric == REQUEST_TAINTED
    drained[0].type == 'count'
    drained[0].value.longValue() == 1
  }

  void 'test telemetry: #description'() {
    setup:
    final handler = new TelemetryRequestEndedHandler(delegate)
    final collector = ctx.metricCollector
    metrics.each { collector.addMetric(it.metric, it.tagValue, it.value) }

    when:
    handler.apply(reqCtx, span)

    then: 'request scoped metrics are propagated to the span'
    1 * delegate.apply(reqCtx, span)
    metrics.findAll { it.metric.scope == Scope.REQUEST }.each {
      1 * traceSegment.setTagTop(TRACE_METRIC_PREFIX + getSpanTagValue(it.metric, it.tagValue), it.value)
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
      metric(REQUEST_TAINTED, 123),
      metric(EXECUTED_SOURCE, SourceTypes.REQUEST_PARAMETER_VALUE, 2),
      metric(EXECUTED_SOURCE, SourceTypes.REQUEST_HEADER_VALUE, 4),
      metric(EXECUTED_SINK, VulnerabilityTypes.SQL_INJECTION, 1),
      metric(EXECUTED_SINK, VulnerabilityTypes.COMMAND_INJECTION, 2),
    ]                                                                      | 'List of only request scoped metrics'
    [
      metric(REQUEST_TAINTED, 123),
      metric(INSTRUMENTED_SOURCE, SourceTypes.REQUEST_PARAMETER_VALUE, 2),
    ]                                                                      | 'Mix between global and request scoped metrics'
  }

  private static String getSpanTagValue(final IastMetric metric, final Byte tagValue = null) {
    return metric.getTag() == null
      ? metric.getName()
      : String.format("%s.%s", metric.getName(), metric.tag.values[tagValue].toLowerCase().replaceAll("\\.", "_"))
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
