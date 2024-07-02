package datadog.trace.common.sampling

import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.api.sampling.PrioritySampling

import java.time.Clock

class AsmStandaloneSamplerTest extends DDCoreSpecification{

  def writer = new ListWriter()

  void "test setSamplingPriority"(){
    setup:
    def current = System.currentTimeMillis()
    def callCount = 0
    final Clock clock = Mock(Clock) {
      millis() >> {
        callCount++
        if (callCount < 4) {
          current += 1000 // increment in one second
        } else {
          current += 60000 // increment in one minute
        }
        return current
      }
    }
    def sampler = new AsmStandaloneSampler(clock)
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span1 = tracer.buildSpan("test").start()
    sampler.setSamplingPriority(span1)

    then:
    span1.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    when:
    def span2 = tracer.buildSpan("test2").start()
    sampler.setSamplingPriority(span2)

    then:
    span2.getSamplingPriority() == PrioritySampling.SAMPLER_DROP

    when:
    def span3 = tracer.buildSpan("test3").start()

    then: "Mock one minute later"
    sampler.setSamplingPriority(span3)

    and:
    span3.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }
}
