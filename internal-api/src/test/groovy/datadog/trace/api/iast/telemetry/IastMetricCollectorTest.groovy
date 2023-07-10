package datadog.trace.api.iast.telemetry

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

import static datadog.trace.api.iast.telemetry.IastMetricCollector.HasMetricCollector
import static datadog.trace.api.iast.telemetry.IastMetricCollector.NoOpInstance

@CompileDynamic
class IastMetricCollectorTest extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  private HasMetricCollector iastCtx
  private RequestContext ctx
  private AgentSpan span
  private AgentTracer.TracerAPI api
  private ExecutorService executor

  void setup() {
    executor = Executors.newFixedThreadPool(8)
    iastCtx = Mock(HasMetricCollector)
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

  void 'test empty collector'() {
    given:
    final requestCollector = new IastMetricCollector()

    when:
    requestCollector.prepareMetrics()
    final result = requestCollector.drain()

    then:
    result.empty
  }

  void 'test collectors'() {
    given:
    final times = 1000
    final value = 5
    final total = times * value
    final latch = new CountDownLatch(1)
    final requestCollector = new IastMetricCollector()
    final random = new Random()
    final metrics = IastMetric.values()
    iastCtx.getMetricCollector() >> requestCollector

    when:
    final futures = (1..times).collect { i ->
      executor.submit {
        final metric = metrics[random.nextInt(metrics.length)]
        final tag = metric.tag == null ? null : metric.tag.values[random.nextInt(metric.tag.values.length)]
        latch.await()
        IastMetricCollector.add(metric, tag, value)
      }
    }
    latch.countDown()
    futures*.get(10, TimeUnit.SECONDS).size()
    requestCollector.prepareMetrics()
    IastMetricCollector.get().merge(requestCollector.drain())

    then:
    IastMetricCollector.get().prepareMetrics()
    final result = IastMetricCollector.get().drain()
    final computedTotal = result*.value.sum() as long
    computedTotal == total
  }

  void 'test no op collector does nothing'() {
    setup:
    final collector = new NoOpInstance()
    final metric = IastMetric.EXECUTED_PROPAGATION
    final tag = null

    when:

    when: 'adding a metric'
    collector.addMetric(metric, tag, 23)
    collector.prepareMetrics()
    final collectorMetrics = collector.drain()

    then:
    collectorMetrics.empty

    when: 'merging metrics'
    collector.merge([new IastMetricCollector.IastMetricData(metric, tag, 23)])
    collector.prepareMetrics()
    final mergedMetrics = collector.drain()

    then:
    mergedMetrics.empty
  }

  void 'test global/request scoped metrics'() {
    given:
    final requestCollector = Mock(IastMetricCollector)
    final globalMetric = IastMetric.INSTRUMENTED_SINK
    final requestMetric = IastMetric.EXECUTED_SINK
    final tag = VulnerabilityTypes.SQL_INJECTION
    final value = 1

    when:
    IastMetricCollector.add(globalMetric, tag, value)

    then: 'global metrics do not access the request context'
    0 * _

    when:
    IastMetricCollector.add(requestMetric, tag, value)

    then: 'request metrics require access to the request context'
    1 * api.activeSpan() >> span
    1 * span.getRequestContext() >> ctx
    1 * ctx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * iastCtx.getMetricCollector() >> requestCollector
    1 * requestCollector.addMetric(requestMetric, tag, value)
    0 * _

    when:
    IastMetricCollector.add(requestMetric, tag, value, ctx)

    then: 'if request context is provided no access to the active span is needed'
    1 * ctx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * iastCtx.getMetricCollector() >> requestCollector
    1 * requestCollector.addMetric(requestMetric, tag, value)
    0 * _
  }

  void 'test metric tags/span tags'() {
    given:
    final metric = IastMetric.INSTRUMENTED_SINK
    final tag = VulnerabilityTypes.SQL_INJECTION

    when:
    final withTag = new IastMetricCollector.IastMetricData(metric, tag, 1)

    then:
    withTag.tags == ["${metric.tag.name}:${tag}"]
    withTag.spanTag == "${metric.name}.${tag.toLowerCase().replaceAll('\\.', '_')}"
  }

  void 'test metrics with multiple tags'() {
    given:
    final collector = new IastMetricCollector()
    final metric = IastMetric.INSTRUMENTED_SINK
    final tag = VulnerabilityTypes.RESPONSE_HEADER
    final value = 1

    when:
    collector.addMetric(metric, tag, value)
    collector.prepareMetrics()
    final result = collector.drain()

    then: 'multiple data points are added'

    result.size() == 2
    final grouped = result.groupBy { it.tags.first() }
    VulnerabilityTypes.RESPONSE_HEADER_TYPES.each {
      final tagValue = "${metric.tag.name}:${it}"
      final data = grouped[tagValue].first()
      assert data.metric == metric
      assert data.value.toLong() == value
      assert data.tags == [tagValue]
    }
  }
}
