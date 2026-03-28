package datadog.trace.common.sampling

import datadog.trace.api.ProductTraceSource
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.api.sampling.PrioritySampling

class LlmObsStandaloneSamplerTest extends DDCoreSpecification {

  def writer = new ListWriter()

  void "test LLMOBS spans are kept"() {
    setup:
    def sampler = new LlmObsStandaloneSampler()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when: "LLMOBS span"
    def span = tracer.buildSpan("llm-call").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.LLMOBS)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }

  void "test APM-only spans are dropped"() {
    setup:
    def sampler = new LlmObsStandaloneSampler()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when: "APM-only span (no LLMOBS flag)"
    def span = tracer.buildSpan("http-request").start()
    sampler.setSamplingPriority(span)

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_DROP

    cleanup:
    tracer.close()
  }

  void "test sample method always returns true"() {
    setup:
    def sampler = new LlmObsStandaloneSampler()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("test").start()

    then:
    sampler.sample(span) == true

    cleanup:
    tracer.close()
  }
}