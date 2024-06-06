package datadog.trace.common.sampling

import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.api.sampling.PrioritySampling

class AsmStandaloneSamplerTest extends DDCoreSpecification{

  def writer = new ListWriter()

  void "test setSamplingPriority"(){
    setup:
    final rate = 1000 // 1 trace per second
    def sampler = new AsmStandaloneSampler(rate)
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

    then: "we wait for one second"
    Thread.sleep(rate)
    sampler.setSamplingPriority(span3)

    and:
    span3.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }
}
