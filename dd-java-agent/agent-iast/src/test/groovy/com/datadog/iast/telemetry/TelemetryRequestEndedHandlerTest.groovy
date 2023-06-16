package com.datadog.iast.telemetry

import com.datadog.iast.IastRequestContext
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Source
import com.datadog.iast.taint.TaintedObjects
import com.datadog.iast.telemetry.taint.TaintedObjectsWithTelemetry
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.telemetry.IastMetric
import datadog.trace.api.iast.telemetry.IastMetricCollector
import datadog.trace.api.iast.telemetry.Verbosity
import groovy.transform.CompileDynamic
import groovy.transform.ToString

import static com.datadog.iast.telemetry.TelemetryRequestEndedHandler.TRACE_METRIC_PATTERN
import static datadog.trace.api.iast.telemetry.IastMetric.*

@CompileDynamic
class TelemetryRequestEndedHandlerTest extends AbstractTelemetryCallbackTest {

  protected RequestEndedHandler delegate
  protected IastRequestContext iastCtx
  protected IastMetricCollector globalCollector

  void setup() {
    delegate = Spy(new RequestEndedHandler(dependencies))
    final TaintedObjects to = TaintedObjectsWithTelemetry.build(Verbosity.DEBUG, TaintedObjects.acquire())
    iastCtx = new IastRequestContext(to, new IastMetricCollector())
    reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    globalCollector = IastMetricCollector.get()
    globalCollector.prepareMetrics()
    globalCollector.drain()
  }

  void 'request ends propagates tainted map metrics'() {
    given:
    final handler = new TelemetryRequestEndedHandler(delegate)
    final toTaint = 'hello'
    final source = new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value')
    iastCtx.taintedObjects.taintInputString(toTaint, source)

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
    final metric = TAINTED_FLAT_MODE

    when:
    iastCtx.metricCollector.addMetric(metric, 1)
    handler.apply(reqCtx, span)

    then:
    1 * delegate.apply(reqCtx, span)
    1 * traceSegment.setTagTop(String.format(TRACE_METRIC_PATTERN, metric.spanTag), 1)

    when:
    globalCollector.prepareMetrics()
    final drained = globalCollector.drain()

    then:
    drained.size() == 1
    drained[0].metric == TAINTED_FLAT_MODE
    drained[0].counter == 1
  }

  void 'test telemetry: #description'() {
    setup:
    final handler = new TelemetryRequestEndedHandler(delegate)
    final collector = iastCtx.metricCollector
    metrics.each { collector.addMetric(it.metric, it.value) }

    when:
    handler.apply(reqCtx, span)

    then: 'request scoped metrics are propagated to the span'
    1 * delegate.apply(reqCtx, span)
    metrics.findAll { it.metric.scope == Scope.REQUEST }.each {
      1 * traceSegment.setTagTop(String.format(TRACE_METRIC_PATTERN, it.metric.spanTag), it.value)
    }

    when:
    globalCollector.prepareMetrics()
    final drained = globalCollector.drain()

    then: 'all metrics are propagated to the global collector'
    metrics.every {
      drained.find { d -> d.metric == it.metric }?.counter == it.value
    }

    where:
    metrics                                                    | description
    [
      metric(REQUEST_TAINTED, 123),
      metric(EXECUTED_SOURCE_REQUEST_PARAMETER_VALUE, 2L),
      metric(EXECUTED_SOURCE_REQUEST_HEADER_VALUE, 4L),
      metric(EXECUTED_SINK_SQL_INJECTION, 1L),
      metric(EXECUTED_SINK_COMMAND_INJECTION, 2L),
    ]                                                          | 'List of only request scoped metrics'
    [
      metric(REQUEST_TAINTED, 123),
      metric(INSTRUMENTED_SOURCE_REQUEST_PARAMETER_VALUE, 2L),
    ]                                                          | 'Mix between global and request scoped metrics'
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
