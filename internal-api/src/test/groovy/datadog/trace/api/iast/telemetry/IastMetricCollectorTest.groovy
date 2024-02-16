package datadog.trace.api.iast.telemetry

import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
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

  void 'test collector in heavy concurrency'() {
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
        latch.await()
        if (metric.tag == null) {
          IastMetricCollector.add(metric, value)
        } else {
          final tag = (byte) random.nextInt(metric.tag.values.length)
          IastMetricCollector.add(metric, tag, value)
        }
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
    final metric = IastMetric.EXECUTED_SINK
    final tag = VulnerabilityTypes.SQL_INJECTION

    when: 'adding a metric'
    collector.addMetric(metric, tag, 1)
    collector.prepareMetrics()
    final collectorMetrics = collector.drain()

    then:
    collectorMetrics.empty

    when: 'merging metrics'
    collector.merge([new IastMetricCollector.IastMetricData(metric, tag, 100)])
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

  void 'test metric tags/span tags: #metric'() {
    when:
    final data = new IastMetricCollector.IastMetricData(metric, tag, 1)

    then:
    if (metric.tag) {
      final tagString = metric.tag.values[tag]
      data.tags.size() == 1
      data.tags.first() == "${metric.tag.name}:${tagString}"
      data.spanTag == "${metric.name}.${tagString.toLowerCase().replaceAll('\\.', '_')}"
    } else {
      data.tags.empty
      data.spanTag == metric.name
    }

    where:
    metric                         | tag
    IastMetric.INSTRUMENTED_SINK   | VulnerabilityTypes.SQL_INJECTION
    IastMetric.INSTRUMENTED_SOURCE | SourceTypes.REQUEST_HEADER_NAME
    IastMetric.EXECUTED_TAINTED    | (byte) -1
  }

  void 'test metric: #metric'() {
    given:
    final collector = new IastMetricCollector()
    final value = 125

    when:
    collector.addMetric(metric, metric.tag == null ? (byte) -1 : tag, value)
    collector.prepareMetrics()
    final result = collector.drain()

    then:
    if (metric.tag) {
      def assertedTags = metric.tag.unwrap(tag) ?: [tag]
      result.size() == assertedTags
      final grouped = result.groupBy { it.tags.first() }
      assertedTags.each {
        final tagValue = "${metric.tag.name}:${metric.tag.values[it as byte]}"
        final data = grouped[tagValue].first()
        assert data.metric == metric
        assert data.value.toLong() == value
        assert data.tags.size() == 1
        assert data.tags.first() == tagValue
      }
    } else {
      result.size() == 1
      final data = result.first()
      data.metric == metric
      data.value.toLong() == value
      data.tags.empty
    }

    where:
    metric                         | tag
    IastMetric.INSTRUMENTED_SINK   | VulnerabilityTypes.SQL_INJECTION
    IastMetric.EXECUTED_SINK       | VulnerabilityTypes.SQL_INJECTION

    IastMetric.INSTRUMENTED_SINK   | VulnerabilityTypes.RESPONSE_HEADER // wrapped response headers
    IastMetric.EXECUTED_SINK       | VulnerabilityTypes.RESPONSE_HEADER

    IastMetric.INSTRUMENTED_SINK   | VulnerabilityTypes.SPRING_RESPONSE // wrapped spring response
    IastMetric.EXECUTED_SINK       | VulnerabilityTypes.SPRING_RESPONSE

    IastMetric.INSTRUMENTED_SOURCE | SourceTypes.REQUEST_HEADER_NAME
    IastMetric.EXECUTED_SOURCE     | SourceTypes.REQUEST_HEADER_NAME

    IastMetric.EXECUTED_TAINTED    | null
  }
}
