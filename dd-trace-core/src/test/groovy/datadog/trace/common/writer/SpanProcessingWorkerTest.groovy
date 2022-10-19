package datadog.trace.common.writer

import datadog.trace.common.sampling.SingleSpanSampler
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.LinkedBlockingDeque

class SpanProcessingWorkerTest extends DDSpecification {

  def "should send only sampled spans to the sampled span queue"() {
    setup:
    Queue<Object> sampledSpanQueue = new LinkedBlockingDeque<>(10)
    SingleSpanSampler singleSpanSampler = Mock(SingleSpanSampler)
    SpanProcessingWorker worker = SpanProcessingWorker.build(10, sampledSpanQueue, singleSpanSampler)
    worker.start()
    DDSpan span1 = Mock(DDSpan)
    DDSpan span2 = Mock(DDSpan)
    DDSpan span3 = Mock(DDSpan)
    singleSpanSampler.setSamplingPriority(span1) >> true
    singleSpanSampler.setSamplingPriority(span2) >> false
    singleSpanSampler.setSamplingPriority(span3) >> true

    when:
    worker.publish([span1, span2, span3])

    then:
    sampledSpanQueue.take() == [span1, span3]
  }
}
