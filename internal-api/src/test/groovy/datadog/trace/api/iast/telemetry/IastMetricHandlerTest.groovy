package datadog.trace.api.iast.telemetry

import datadog.trace.api.iast.telemetry.IastTelemetryCollector.MetricData
import datadog.trace.api.iast.telemetry.IastTelemetryCollector.Point
import groovy.transform.CompileDynamic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.FirstParam
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@CompileDynamic
class IastMetricHandlerTest extends Specification {

  private ExecutorService executor

  void setup() {
    executor = Executors.newFixedThreadPool(8)
  }

  void cleanup() {
    executor.shutdown()
  }

  void 'test conflated combiner'() {
    given:
    final times = 100
    final value = 5
    final combiner = new IastMetricHandler.ConflatedCombiner()

    when:
    final initialPoints = combiner.drain()

    then:
    initialPoints.empty

    when:
    testCombiner(combiner, times, value)

    then:
    final points = combiner.drain()
    points.size() == 1
    points.get(0).value == times * value

    when:
    final metric = new MetricData(null, [new Point(1), new Point(2), new Point(3)])
    combiner.merge(metric)

    then:
    final merged = combiner.drain()
    merged.size() == 1
    merged.get(0).value == metric.points*.value.sum()
  }

  void 'test aggregated combiner'() {
    given:
    final times = 100
    final value = 5
    final combiner = new IastMetricHandler.AggregatedCombiner()

    when:
    final initialPoints = combiner.drain()

    then:
    initialPoints.empty

    when:
    testCombiner(combiner, times, value)

    then:
    final points = combiner.drain()
    points.size() == times
    points.each {
      assert it.value == value
    }

    when:
    final metric = new MetricData(null, [new Point(1), new Point(2), new Point(3)])
    combiner.merge(metric)

    then:
    final merged = combiner.drain()
    merged.size() == metric.points.size()
    merged.eachWithIndex { point, int i ->
      assert point.value == metric.points[i].value
    }
  }

  void 'test default handler'() {
    given:
    final times = 100
    final value = 5
    final iastMetric = IastMetric.EXECUTED_PROPAGATION
    final combiner = Mock(IastMetricHandler.Combiner)
    final handler = new IastMetricHandler.DefaultHandler(iastMetric, combiner)

    when:
    final initialMetrics = handler.drain()

    then:
    1 * combiner.drain() >> []
    initialMetrics.empty

    when:
    testHandler(handler, times, value)

    then:
    times * combiner.add(value)

    when:
    final result = handler.drain()

    then:
    1 * combiner.drain() >> [new Point(23)]
    result.size() == 1
    final firstMetric = result.first()
    firstMetric.metric == iastMetric
    firstMetric.points.size() == 1
    firstMetric.points.first().value == 23

    when:
    handler.merge(new MetricData(iastMetric, [new Point(1), new Point(2), new Point(3)]))

    then:
    1 * combiner.merge(_)
  }

  void 'test tagged handler'() {
    given:
    final times = 100
    final value = 5
    final total = times * value
    final iastMetric = IastMetric.EXECUTED_SINK
    final supplier = {
      new IastMetricHandler.ConflatedCombiner()
    } as Supplier<IastMetricHandler.Combiner>
    final tags = ['tag1', 'tag2']
    final handler = new IastMetricHandler.TaggedHandler(iastMetric, supplier)

    when:
    final initialMetrics = handler.drain()

    then:
    initialMetrics.empty

    when:
    testHandler(handler, times, value, tags as String[])

    then:
    final computedValue = sumMetrics(handler.drain()) { it.metric == iastMetric }
    computedValue == total

    when:
    handler.merge(new MetricData(iastMetric, tags[0], [new Point(1), new Point(2), new Point(3)]))

    then:
    final merged = sumMetrics(handler.drain()) { it.metric == iastMetric }
    merged == 6
  }

  void 'test delegated handler'() {
    given:
    final times = 100
    final value = 5
    final iastMetric = IastMetric.EXECUTED_PROPAGATION
    final delegate = Mock(IastTelemetryCollector)
    final handler = IastMetricHandler.delegating(iastMetric, delegate)

    when:
    testHandler(handler, times, value)

    then: 'global handle does not drain directly'
    handler.drain().empty
    times * delegate.addMetric(iastMetric, value, _)

    when:
    handler.merge(new MetricData(iastMetric, [new Point(1), new Point(2), new Point(3)]))

    then: 'global handler does not merge metrics'
    handler.drain().empty
    0 * delegate.addMetric(_, _, _)
  }

  private int testCombiner(final IastMetricHandler.Combiner combiner, final int times, final int value) {
    final latch = new CountDownLatch(1)
    final futures = (1..times).collect {
      executor.submit {
        latch.await()
        combiner.add(value)
      }
    }
    latch.countDown()
    return futures*.get(10, TimeUnit.SECONDS).size()
  }

  private int testHandler(final IastMetricHandler handler, final int times, final int value, final String...tags) {
    final latch = new CountDownLatch(1)
    final futures = (1..times).collect { i ->
      executor.submit {
        final tagValue = tags.length == 0 ? null : tags[i % tags.length]
        latch.await()
        handler.add(value, tagValue)
      }
    }
    latch.countDown()
    return futures*.get(10, TimeUnit.SECONDS).size()
  }

  private static long sumMetrics(final Collection<MetricData> metrics,
    final @ClosureParams(FirstParam.FirstGenericType) Closure<Boolean> filter) {
    return metrics.findAll(filter).collectMany { it.points }*.value.sum() as long
  }
}
