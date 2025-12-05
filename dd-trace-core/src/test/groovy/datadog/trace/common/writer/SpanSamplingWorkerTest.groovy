package datadog.trace.common.writer

import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.sampling.SingleSpanSampler
import datadog.trace.core.DDSpan
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification
import static SpanSamplingWorker.DefaultSpanSamplingWorker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class SpanSamplingWorkerTest extends DDSpecification {

  def "send only sampled spans to the sampled span queue"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy)
    worker.start()
    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    DDSpan span3 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span1) >> true
    singleSpanSampler.setSamplingPriority(span2) >> false
    singleSpanSampler.setSamplingPriority(span3) >> true

    when:
    worker.getSpanSamplingQueue().offer([span1, span2, span3])

    then:
    primaryQueue.take() == [span1, span3]
    secondaryQueue.take() == [span2]

    cleanup:
    worker.close()
  }

  def "handle multiple traces"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy)
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    DDSpan span3 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span1) >> true
    singleSpanSampler.setSamplingPriority(span2) >> false
    singleSpanSampler.setSamplingPriority(span3) >> true

    DDSpan span4 = Mock(DDSpan)
    DDSpan span5 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span4) >> true
    singleSpanSampler.setSamplingPriority(span5) >> false

    when:
    worker.getSpanSamplingQueue().offer([span1, span2, span3])
    worker.getSpanSamplingQueue().offer([span4, span5])

    then:
    primaryQueue.take() == [span1, span3]
    secondaryQueue.take() == [span2]
    primaryQueue.take() == [span4]
    secondaryQueue.take() == [span5]

    cleanup:
    worker.close()
  }

  def "skip traces with no sampled spans"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy)
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    DDSpan span3 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span1) >> true
    singleSpanSampler.setSamplingPriority(span2) >> false
    singleSpanSampler.setSamplingPriority(span3) >> true

    DDSpan span4 = Mock(DDSpan)
    DDSpan span5 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span4) >> false
    singleSpanSampler.setSamplingPriority(span5) >> false

    DDSpan span6 = Mock(DDSpan)
    DDSpan span7 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span6) >> true
    singleSpanSampler.setSamplingPriority(span7) >> true

    when:
    assert worker.getSpanSamplingQueue().offer([span1, span2, span3])
    assert worker.getSpanSamplingQueue().offer([span4, span5])
    assert worker.getSpanSamplingQueue().offer([span6, span7])

    then:
    primaryQueue.take() == [span1, span3]
    secondaryQueue.take() == [span2]
    secondaryQueue.take() == [span4, span5]
    primaryQueue.take() == [span6, span7]

    cleanup:
    worker.close()
  }

  def "ignore empty traces"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy)
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span1) >> true

    when:
    assert worker.getSpanSamplingQueue().offer([])
    assert worker.getSpanSamplingQueue().offer([span1])

    then:
    primaryQueue.take() == [span1]
    assert secondaryQueue.isEmpty()

    cleanup:
    worker.close()
  }

  def "update dropped traces metric when no tracer's spans have been sampled"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    int expectedTraces = 1
    CountDownLatch latch = new CountDownLatch(expectedTraces)
    SpanSamplingWorker worker = new DefaultSpanSamplingWorker(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy) {
        @Override
        protected void afterOnEvent() {
          latch.countDown()
        }
      }
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    span1.samplingPriority() >> PrioritySampling.USER_DROP
    singleSpanSampler.setSamplingPriority(span1) >> false
    singleSpanSampler.setSamplingPriority(span2) >> false


    def queue = worker.getSpanSamplingQueue()

    when:
    assert queue.offer([span1, span2])

    then:
    latch.await(10, TimeUnit.SECONDS)

    then:
    assert primaryQueue.isEmpty()
    secondaryQueue.take() == [span1, span2]

    then:
    1 * healthMetrics.onPublish([span1, span2], PrioritySampling.USER_DROP)
    0 * healthMetrics.onFailedPublish(_,_)
    0 * healthMetrics.onPartialPublish(_)

    cleanup:
    worker.close()
  }

  def "update dropped traces metric when primaryQueue is full"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(1)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    primaryQueue.offer([]) // occupy the entire queue
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    int expectedTraces = 1
    CountDownLatch latch = new CountDownLatch(expectedTraces)
    SpanSamplingWorker worker = new DefaultSpanSamplingWorker(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy) {
        @Override
        protected void afterOnEvent() {
          latch.countDown()
        }
      }
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    span1.samplingPriority() >> PrioritySampling.SAMPLER_DROP
    singleSpanSampler.setSamplingPriority(span1) >> false
    singleSpanSampler.setSamplingPriority(span2) >> true


    def queue = worker.getSpanSamplingQueue()

    when:
    assert queue.offer([span1, span2])

    then:
    latch.await(10, TimeUnit.SECONDS)

    then:
    assert secondaryQueue.isEmpty()

    then:
    1 * healthMetrics.onFailedPublish(PrioritySampling.SAMPLER_DROP,_)
    0 * healthMetrics.onPublish(_, _)
    0 * healthMetrics.onPartialPublish(_)

    cleanup:
    worker.close()
  }

  def "update published traces metric when all trace's spans have been sampled"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy)
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    span1.samplingPriority() >> PrioritySampling.SAMPLER_DROP
    singleSpanSampler.setSamplingPriority(span1) >> true
    singleSpanSampler.setSamplingPriority(span2) >> true

    def queue = worker.getSpanSamplingQueue()

    when:
    assert queue.offer([span1, span2])

    then:
    primaryQueue.take() == [span1, span2]
    assert secondaryQueue.isEmpty()

    then:
    1 * healthMetrics.onPublish([span1, span2], PrioritySampling.SAMPLER_DROP)
    0 * healthMetrics.onFailedPublish(_,_)
    0 * healthMetrics.onPartialPublish(_)

    cleanup:
    worker.close()
  }

  def "update partial traces metric when some trace's spans have been dropped and sent to secondaryQueue"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10)
    def droppingPolicy = { false }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy)
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    DDSpan span3 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span1) >> false
    singleSpanSampler.setSamplingPriority(span2) >> true
    singleSpanSampler.setSamplingPriority(span3) >> false

    def queue = worker.getSpanSamplingQueue()

    when:
    assert queue.offer([span1, span2, span3])

    then:
    primaryQueue.take() == [span2]
    secondaryQueue.take() == [span1, span3]

    then:
    1 * healthMetrics.onPublish([span1, span2, span3], PrioritySampling.SAMPLER_DROP)
    0 * healthMetrics.onPartialPublish(_)
    0 * healthMetrics.onFailedPublish(_,_)

    cleanup:
    worker.close()
  }

  def "update partial traces metric when some trace's spans have been dropped and secondaryQueue is full or droppingPolicy is active"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(secondaryQueueIsFull ? 1 : 10)
    if (secondaryQueueIsFull) {
      // occupy the entire queue
      secondaryQueue.offer([])
    }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = new DefaultSpanSamplingWorker(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, { droppingPolicy })
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    DDSpan span3 = Mock(DDSpan)
    span1.samplingPriority() >> PrioritySampling.SAMPLER_DROP
    singleSpanSampler.setSamplingPriority(span1) >> false
    singleSpanSampler.setSamplingPriority(span2) >> true
    singleSpanSampler.setSamplingPriority(span3) >> false

    def queue = worker.getSpanSamplingQueue()

    when:
    assert queue.offer([span1, span2, span3])

    then:
    primaryQueue.take() == [span2]

    then:
    1 * healthMetrics.onPartialPublish(2)
    0 * healthMetrics.onFailedPublish(_,_)
    0 * healthMetrics.onPublish(_, _)

    cleanup:
    worker.close()

    where:
    droppingPolicy | secondaryQueueIsFull
    true           | false
    false          | true
    true           | true
  }

  def "update FailedPublish metric when all trace's spans have been dropped and secondaryQueue is full or droppingPolicy is active"() {
    setup:
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10)
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(secondaryQueueIsFull ? 1 : 10)
    if (secondaryQueueIsFull) {
      // occupy the entire queue
      secondaryQueue.offer([])
    }
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    int expectedTraces = 1
    CountDownLatch latch = new CountDownLatch(expectedTraces)
    SpanSamplingWorker worker = new DefaultSpanSamplingWorker(10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, { droppingPolicy }) {
      @Override
      protected void afterOnEvent() {
        latch.countDown()
      }
    }
    worker.start()

    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    span1.samplingPriority() >> PrioritySampling.SAMPLER_DROP
    singleSpanSampler.setSamplingPriority(span1) >> false
    singleSpanSampler.setSamplingPriority(span2) >> false

    def queue = worker.getSpanSamplingQueue()

    when:
    assert queue.offer([span1, span2])

    then:
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * healthMetrics.onFailedPublish(PrioritySampling.SAMPLER_DROP,_)
    0 * healthMetrics.onPartialPublish(_)
    0 * healthMetrics.onPublish(_, _)

    cleanup:
    worker.close()

    where:
    droppingPolicy | secondaryQueueIsFull
    true           | false
    false          | true
    true           | true
  }
}
