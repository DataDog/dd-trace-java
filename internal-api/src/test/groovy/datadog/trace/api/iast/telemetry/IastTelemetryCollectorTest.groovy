package datadog.trace.api.iast.telemetry

import datadog.trace.api.config.IastConfig
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.VulnerabilityTypes
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST
import static datadog.trace.api.iast.telemetry.IastMetricHandler.conflated
import static datadog.trace.api.iast.telemetry.IastMetricHandler.delegating

@CompileDynamic
class IastTelemetryCollectorTest extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  private IastTelemetryCollector.HasTelemetryCollector iastCtx
  private RequestContext ctx
  private AgentSpan span
  private AgentTracer.TracerAPI api
  private ExecutorService executor

  void setup() {
    executor = Executors.newFixedThreadPool(8)
    iastCtx = Mock(IastTelemetryCollector.HasTelemetryCollector)
    ctx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> iastCtx
    }
    span = Mock(AgentSpan) {
      getRequestContext() >> ctx
    }
    api = Mock(AgentTracer.TracerAPI) {
      activeSpan() >> span
    }
    AgentTracer.forceRegister(api)
  }

  void cleanup() {
    executor.shutdown()
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  void 'test collectors'() {
    given:
    final times = 1000
    final value = 5
    final total = times * value
    final latch = new CountDownLatch(1)
    final requestCollector = new IastTelemetryCollectorImpl({ IastMetric metric ->
      metric.getScope() == REQUEST
        ? conflated(metric)
        : delegating(metric, IastTelemetryCollector.Holder.GLOBAL)
    })
    iastCtx.getTelemetryCollector() >> requestCollector
    final tags = ['tag1', 'tag2', 'tag3', 'tag4']

    when:
    final futures = (1..times).collect { i ->
      executor.submit {
        final metric = IastMetric.values()[i % IastMetric.values().length]
        final tag = metric.tag != null ? tags[i % tags.size()] : null
        latch.await()
        if (tag == null) {
          IastTelemetryCollector.add(metric, value)
        } else {
          IastTelemetryCollector.add(metric, value, tag)
        }
      }
    }
    latch.countDown()
    futures*.get(10, TimeUnit.SECONDS).size()
    IastTelemetryCollector.Holder.GLOBAL.merge(requestCollector.drainMetrics())

    then:
    final metrics = IastTelemetryCollector.drain()
    final computedTotal = metrics.collectMany { it.points }*.value.sum() as long
    computedTotal == total
  }

  void 'test failing handler does not propagate'() {
    given:
    final failingHandler = Mock(IastMetricHandler)
    final requestCollector = new IastTelemetryCollectorImpl({ IastMetric metric ->
      failingHandler
    })
    iastCtx.getTelemetryCollector() >> requestCollector

    when:
    IastTelemetryCollector.add(IastMetric.EXECUTED_PROPAGATION, 1)

    then:
    noExceptionThrown()
    1 * failingHandler.add(_, _) >> { throw new IllegalStateException('BOOM!!!') }

    when:
    IastTelemetryCollector.add(IastMetric.EXECUTED_SINK, 1, VulnerabilityTypes.WEAK_CIPHER)

    then:
    noExceptionThrown()
    1 * failingHandler.add(_, _) >> { throw new IllegalStateException('BOOM!!!') }
  }

  void 'test no op collector does nothing'() {
    setup:
    final collector = collectorProvider.call()

    when:
    collector.addMetric(IastMetric.EXECUTED_SINK, 23, 'tag')

    then:
    collector.drainMetrics().empty

    when:
    collector.merge([
      new IastTelemetryCollector.MetricData(IastMetric.EXECUTED_SINK, 'tag', [new IastTelemetryCollector.Point(23)])
    ])

    then:
    collector.drainMetrics().empty

    where:
    collectorProvider << [
      { return new NoOpTelemetryCollector() },
      {
        injectSysConfig(IastConfig.IAST_TELEMETRY_VERBOSITY, 'OFF')
        return IastTelemetryCollector.Holder.globalCollector()
      }
    ]
  }

  void 'test global/request scoped metrics'() {
    given:
    final requestCollector = Mock(IastTelemetryCollector)
    final globalMetric = IastMetric.INSTRUMENTED_SINK
    final requestMetric = IastMetric.EXECUTED_SINK
    final metricTag = VulnerabilityTypes.WEAK_CIPHER
    final value = 1L

    when:
    IastTelemetryCollector.add(globalMetric, value, metricTag)

    then: 'global metrics do not access the request context'
    0 * _

    when:
    IastTelemetryCollector.add(requestMetric, value, metricTag)

    then: 'request metrics require access to the request context'
    1 * api.activeSpan() >> span
    1 * span.getRequestContext() >> ctx
    1 * ctx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * iastCtx.getTelemetryCollector() >> requestCollector
    1 * requestCollector.addMetric(requestMetric, value, metricTag)
    0 * _

    when:
    IastTelemetryCollector.add(requestMetric, value, metricTag, ctx)

    then: 'if request context is provided no access to the active span is needed'
    1 * ctx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * iastCtx.getTelemetryCollector() >> requestCollector
    1 * requestCollector.addMetric(requestMetric, value, metricTag)
    0 * _
  }
}
