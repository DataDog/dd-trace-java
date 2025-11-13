package datadog.trace.common.sampling

import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.api.sampling.PrioritySampling

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

class AsmStandaloneSamplerTest extends DDCoreSpecification{

  def writer = new ListWriter()

  void "test setSamplingPriority"(){
    setup:
    def current = new AtomicLong(System.currentTimeMillis())
    final Clock clock = Mock(Clock) {
      millis() >> {
        current.get()
      }
    }
    def sampler = new AsmStandaloneSampler(clock)
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span1 = tracer.buildSpan("test").start()
    sampler.setSamplingPriority(span1)

    then:
    1 * clock.millis() >> {
      current.updateAndGet(value -> value + 1000)
    } // increment in one second
    span1.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    when:
    def span2 = tracer.buildSpan("test2").start()
    sampler.setSamplingPriority(span2)

    then:
    1 * clock.millis() >> {
      current.updateAndGet(value -> value + 1000)
    } // increment in one second
    span2.getSamplingPriority() == PrioritySampling.SAMPLER_DROP

    when:
    def span3 = tracer.buildSpan("test3").start()
    sampler.setSamplingPriority(span3)

    then: "Mock one minute later"
    clock.millis() >> {
      current.updateAndGet(value -> value + 60000)
    } // increment in one minute
    span3.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }
}
