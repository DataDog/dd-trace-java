package datadog.trace.common.sampling

import datadog.trace.api.ProductTraceSource
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

class LlmObsAndAsmStandaloneSamplerTest extends DDCoreSpecification {

  def writer = new ListWriter()

  void "test LLMObs spans are kept"() {
    setup:
    def sampler = new LlmObsAndAsmStandaloneSampler(Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "llm-call").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.LLMOBS)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }

  void "test ASM spans are kept"() {
    setup:
    def sampler = new LlmObsAndAsmStandaloneSampler(Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "http-request").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }

  void "test APM-only spans are rate-limited to 1 per minute"() {
    setup:
    def current = new AtomicLong(System.currentTimeMillis())
    final Clock clock = Mock(Clock) {
      millis() >> {
        current.get()
      }
    }
    def sampler = new LlmObsAndAsmStandaloneSampler(clock)
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when: "first APM span"
    def span1 = tracer.buildSpan("testInstrumentation", "apm-request").start()
    sampler.setSamplingPriority(span1)

    then:
    1 * clock.millis() >> {
      current.updateAndGet(v -> v + 1000)
    }
    span1.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    when: "second APM span within the same minute"
    def span2 = tracer.buildSpan("testInstrumentation", "apm-request2").start()
    sampler.setSamplingPriority(span2)

    then:
    1 * clock.millis() >> {
      current.updateAndGet(v -> v + 1000)
    }
    span2.getSamplingPriority() == PrioritySampling.SAMPLER_DROP

    when: "third APM span after 1 minute"
    def span3 = tracer.buildSpan("testInstrumentation", "apm-request3").start()
    sampler.setSamplingPriority(span3)

    then:
    clock.millis() >> {
      current.updateAndGet(v -> v + 60000)
    }
    span3.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }
}
