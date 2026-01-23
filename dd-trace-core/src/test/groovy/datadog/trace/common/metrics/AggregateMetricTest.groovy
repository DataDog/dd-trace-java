package datadog.trace.common.metrics

import datadog.metrics.agent.AgentMeter
import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.statsd.StatsDClient
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.BlockingDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG
import static datadog.trace.common.metrics.AggregateMetric.TOP_LEVEL_TAG

class AggregateMetricTest extends DDSpecification {

  def setupSpec() {
    // Initialize AgentMeter with monitoring - this is the standard mechanism used in production
    def monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring)
    // Create a timer to trigger DDSketchHistograms loading and Factory registration
    // This simulates what happens during CoreTracer initialization (traceWriteTimer)
    monitoring.newTimer("test.init")
  }

  def "record durations sums up to total"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3))
    then:
    aggregate.getDuration() == 6
  }

  def "total durations include errors"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3))
    then:
    aggregate.getDuration() == 6
  }

  def "clear"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
      .recordDurations(3, new AtomicLongArray(5, ERROR_TAG | 6, TOP_LEVEL_TAG | 7))
    when:
    aggregate.clear()
    then:
    aggregate.getDuration() == 0
    aggregate.getErrorCount() == 0
    aggregate.getTopLevelCount() == 0
    aggregate.getHitCount() == 0
  }

  def "contribute batch with key to aggregate"() {
    given:
    AggregateMetric aggregate = new AggregateMetric().recordDurations(3, new AtomicLongArray(0L, 0L, 0L | ERROR_TAG | TOP_LEVEL_TAG))

    Batch batch = new Batch().reset(new MetricKey("foo", "bar", "qux", "type", 0, false, true, "corge", [UTF8BytesString.create("grault:quux")]))
    batch.add(0L, 10)
    batch.add(0L, 10)
    batch.add(0L, 10)

    when:
    batch.contributeTo(aggregate)

    then: "batch used and values contributed to existing aggregate"
    batch.isUsed()
    aggregate.getDuration() == 30
    aggregate.getHitCount() == 6
    aggregate.getErrorCount() == 1
    aggregate.getTopLevelCount() == 1
  }

  def "ignore used batches"() {
    given:
    AggregateMetric aggregate = new AggregateMetric().recordDurations(10,
      new AtomicLongArray(1L, 1L, 1L, 1L, 1L, 1L, 1L | TOP_LEVEL_TAG, 1L, 1L, 1L | ERROR_TAG))


    Batch batch = new Batch()
    batch.contributeTo(aggregate)
    // must be used now
    batch.add(0L, 10)

    when:
    batch.contributeTo(aggregate)

    then: "batch ignored"
    aggregate.getDuration() == 10
    aggregate.getHitCount() == 10
    aggregate.getErrorCount() == 1
    aggregate.getTopLevelCount() == 1
  }

  def "ignore trailing zeros"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3, 0, 0, 0))
    then:
    aggregate.getDuration() == 6
    aggregate.getHitCount() == 3
    aggregate.getErrorCount() == 0
  }

  def "hit count includes errors"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3 | ERROR_TAG))
    then:
    aggregate.getHitCount() == 3
    aggregate.getErrorCount() == 1
  }

  def "ok and error durations tracked separately"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(10,
      new AtomicLongArray(1, 100 | ERROR_TAG, 2, 99 | ERROR_TAG, 3,
      98  | ERROR_TAG, 4, 97  | ERROR_TAG))
    then:
    def errorLatencies = aggregate.getErrorLatencies()
    def okLatencies = aggregate.getOkLatencies()
    errorLatencies.getMaxValue() >= 99
    okLatencies.getMaxValue() <= 5
  }

  def "consistent under concurrent attempts to read and write"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    MetricKey key = new MetricKey("foo", "bar", "qux", "type", 0, false, true, "corge", [UTF8BytesString.create("grault:quux")])
    BlockingDeque<Batch> queue = new LinkedBlockingDeque<>(1000)
    ExecutorService reader = Executors.newSingleThreadExecutor()
    int writerCount = 10
    ExecutorService writers = Executors.newFixedThreadPool(writerCount)
    CountDownLatch readerLatch = new CountDownLatch(1)
    CountDownLatch writerLatch = new CountDownLatch(writerCount)
    CountDownLatch queueEmptyLatch = new CountDownLatch(1)

    AtomicInteger written = new AtomicInteger(0)

    when:
    for (int i = 0; i < writerCount; ++i) {
      writers.submit({
        readerLatch.await()
        for (int j = 0; j < 10_000; ++j) {
          Batch batch = queue.peekLast()
          if (batch?.add(0L, 1)) {
            written.incrementAndGet()
          } else {
            queue.offer(new Batch().reset(key))
          }
        }
        writerLatch.countDown()
      })
    }
    def future = reader.submit({
      readerLatch.countDown()
      while (!Thread.currentThread().isInterrupted()) {
        Batch batch = queue.poll(100, TimeUnit.MILLISECONDS)
        if (null == batch && writerLatch.count == 0) {
          queueEmptyLatch.countDown()
        } else if (null != batch) {
          batch.contributeTo(aggregate)
        }
      }
    })
    assert writerLatch.await(10, TimeUnit.SECONDS)
    // Wait here until we know that the queue is empty
    assert queueEmptyLatch.await(10, TimeUnit.SECONDS)
    future.cancel(true)

    then:
    aggregate.getHitCount() == written.get()
  }
}
