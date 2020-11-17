package datadog.trace.common.metrics

import datadog.trace.test.util.DDSpecification

import java.util.concurrent.BlockingDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AggregateMetricTest extends DDSpecification {

  def "record durations sums up to total"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(0L, 1, 2, 3)
    then:
    aggregate.getDuration() == 6
  }

  def "clear"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    .recordDurations(0L, 5, 6, 7)
    .addHits(10)
    .addErrors(1)
    when:
    aggregate.clear()
    then:
    aggregate.getDuration() == 0
    aggregate.getErrorCount() == 0
    aggregate.getHitCount() == 0
  }

  def "contribute batch with key to aggregate"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
      .addHits(10)
      .addErrors(1)

    Batch batch = new Batch().withKey(new MetricKey("foo", "bar", "qux", "type", "", 0))
    batch.add(false, 10)
    batch.add(false, 10)
    batch.add(false, 10)

    when:
    batch.contributeTo(aggregate)

    then: "key cleared and values contributed to existing aggregate"
    batch.getKey() == null
    aggregate.getDuration() == 30
    aggregate.getHitCount() == 13
    aggregate.getErrorCount() == 1
  }

  def "ignore batches without keys"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
      .addHits(10)
      .addErrors(1)

    Batch batch = new Batch()
    batch.add(false, 10)

    when:
    batch.contributeTo(aggregate)

    then: "batch ignored"
    aggregate.getDuration() == 0
    aggregate.getHitCount() == 10
    aggregate.getErrorCount() == 1
  }

  def "consistent under concurrent attempts to read and write"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    MetricKey key = new MetricKey("foo", "bar", "qux", "type", "", 0)
    BlockingDeque<Batch> queue = new LinkedBlockingDeque<>(1000)
    ExecutorService reader = Executors.newSingleThreadExecutor()
    int writerCount = 10
    ExecutorService writers = Executors.newFixedThreadPool(writerCount)
    CountDownLatch readerLatch = new CountDownLatch(1)
    CountDownLatch writerLatch = new CountDownLatch(writerCount)

    AtomicInteger written = new AtomicInteger(0)

    when:
    for (int i = 0; i < writerCount; ++i) {
      writers.submit({
        readerLatch.await()
        for (int j = 0; j < 10_000; ++j) {
          Batch batch = queue.peekLast()
          if (batch?.add(false, 1)) {
            written.incrementAndGet()
          } else {
            queue.offer(new Batch().withKey(key))
          }
        }
        writerLatch.countDown()
      })
    }
    def future = reader.submit({
      readerLatch.countDown()
      while (!Thread.currentThread().isInterrupted()) {
        Batch batch = queue.take()
        batch.contributeTo(aggregate)
      }
    })
    writerLatch.await(10, TimeUnit.SECONDS)
    future.cancel(true)

    then:
    aggregate.getHitCount() == written.get()
  }
}
