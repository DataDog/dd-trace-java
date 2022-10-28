package datadog.trace.common.writer

import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.sampling.SingleSpanSampler
import datadog.trace.core.DDSpan
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque

class SpanSamplingWorkerTest extends DDSpecification {

  def "send only sampled spans to the sampled span queue"() {
    setup:
    Queue<Object> sampledSpanQueue = new LinkedBlockingDeque<>(10)
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, sampledSpanQueue, singleSpanSampler, healthMetrics)
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
    sampledSpanQueue.take() == [span1, span3]

    cleanup:
    worker.close()
  }

  def "handle multiple traces"() {
    setup:
    Queue<Object> sampledSpanQueue = new LinkedBlockingDeque<>(10)
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, sampledSpanQueue, singleSpanSampler, healthMetrics)
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
    sampledSpanQueue.take() == [span1, span3]
    sampledSpanQueue.take() == [span4]

    cleanup:
    worker.close()
  }

  def "skip traces with no sampled spans"() {
    setup:
    Queue<Object> sampledSpanQueue = new LinkedBlockingDeque<>(10)
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, sampledSpanQueue, singleSpanSampler, healthMetrics)
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
    worker.getSpanSamplingQueue().offer([span1, span2, span3])
    worker.getSpanSamplingQueue().offer([span4, span5])
    worker.getSpanSamplingQueue().offer([span6, span7])

    then:
    sampledSpanQueue.take() == [span1, span3]
    // second trace [span4, span5] has been dropped b/o none its span has been sampled
    sampledSpanQueue.take() == [span6, span7]

    cleanup:
    worker.close()
  }

  def "update dropped traces metric when no tracer's spans have been sampled"() {
    setup:
    Queue<Object> sampledSpanQueue = new LinkedBlockingDeque<>(10)
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    int expectedTraces = 1
    CountDownLatch latch = new CountDownLatch(expectedTraces)
    SpanSamplingWorker worker = new SpanSamplingWorker(10, sampledSpanQueue, singleSpanSampler, healthMetrics) {
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
    latch.await()

    then:
    1 * healthMetrics.onFailedPublish(PrioritySampling.USER_DROP)
    0 * healthMetrics.onPublish(_, _)

    cleanup:
    worker.close()
  }

  def "update dropped traces metric when queue is full"() {
    setup:
    Queue<Object> sampledSpanQueue = new LinkedBlockingDeque<>(1)
    sampledSpanQueue.offer([]) // occupy the entire queue
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    int expectedTraces = 1
    CountDownLatch latch = new CountDownLatch(expectedTraces)
    SpanSamplingWorker worker = new SpanSamplingWorker(10, sampledSpanQueue, singleSpanSampler, healthMetrics) {
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
    latch.await()

    then:
    1 * healthMetrics.onFailedPublish(PrioritySampling.SAMPLER_DROP)
    0 * healthMetrics.onPublish(_, _)

    cleanup:
    worker.close()
  }

  def "update published traces metric when all trace's spans have been sampled"() {
    setup:
    Queue<Object> sampledSpanQueue = new LinkedBlockingDeque<>(10)
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    SpanSamplingWorker worker = SpanSamplingWorker.build(10, sampledSpanQueue, singleSpanSampler, healthMetrics)
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
    sampledSpanQueue.take() == [span1, span2]

    then:
    1 * healthMetrics.onPublish([span1, span2], PrioritySampling.SAMPLER_DROP)
    0 * healthMetrics.onFailedPublish(_)

    cleanup:
    worker.close()
  }

  //TODO update partialTraces metric
  //TODO update droppedSpans metric
}
