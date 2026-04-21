package datadog.trace.common.sampling

import datadog.trace.api.ProductTraceSource
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

class StandaloneSamplerTest extends DDCoreSpecification {

  def writer = new ListWriter()

  void "LLMOBS only: LLMOBS-marked spans are kept with DEFAULT mechanism"() {
    setup:
    def sampler = new StandaloneSampler([StandaloneProduct.LLMOBS], Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "llm-call").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.LLMOBS)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    span.context().getPropagationTags().createTagMap().get("_dd.p.dm") == "-0"

    cleanup:
    tracer.close()
  }

  void "LLMOBS only: APM-only spans are dropped with DEFAULT mechanism"() {
    setup:
    def sampler = new StandaloneSampler([StandaloneProduct.LLMOBS], Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "http-request").start()
    sampler.setSamplingPriority(span)

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_DROP
    !span.context().getPropagationTags().createTagMap().containsKey("_dd.p.dm")

    cleanup:
    tracer.close()
  }

  void "ASM only: ASM-marked spans are kept with APPSEC mechanism"() {
    setup:
    def sampler = new StandaloneSampler([StandaloneProduct.ASM], Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "http-request").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    span.context().getPropagationTags().createTagMap().get("_dd.p.dm") == "-5"

    cleanup:
    tracer.close()
  }

  void "ASM only: APM-only spans are rate-limited to 1 per minute with APPSEC mechanism"() {
    setup:
    def current = new AtomicLong(System.currentTimeMillis())
    final Clock clock = Mock(Clock) {
      millis() >> {
        current.get()
      }
    }
    def sampler = new StandaloneSampler([StandaloneProduct.ASM], clock)
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when: "first APM span — kept for billing"
    def span1 = tracer.buildSpan("testInstrumentation", "apm-request").start()
    sampler.setSamplingPriority(span1)

    then:
    1 * clock.millis() >> {
      current.updateAndGet(v -> v + 1000)
    }
    span1.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    span1.context().getPropagationTags().createTagMap().get("_dd.p.dm") == "-5"

    when: "second APM span within the same minute — dropped"
    def span2 = tracer.buildSpan("testInstrumentation", "apm-request2").start()
    sampler.setSamplingPriority(span2)

    then:
    1 * clock.millis() >> {
      current.updateAndGet(v -> v + 1000)
    }
    span2.getSamplingPriority() == PrioritySampling.SAMPLER_DROP
    !span2.context().getPropagationTags().createTagMap().containsKey("_dd.p.dm")

    when: "third APM span after 1 minute — kept again"
    def span3 = tracer.buildSpan("testInstrumentation", "apm-request3").start()
    sampler.setSamplingPriority(span3)

    then:
    1 * clock.millis() >> {
      current.updateAndGet(v -> v + 60000)
    }
    span3.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    span3.context().getPropagationTags().createTagMap().get("_dd.p.dm") == "-5"

    cleanup:
    tracer.close()
  }

  void "LLMOBS+ASM: LLMOBS-marked spans are kept with DEFAULT mechanism"() {
    setup:
    def sampler = new StandaloneSampler([StandaloneProduct.LLMOBS, StandaloneProduct.ASM], Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "llm-call").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.LLMOBS)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    span.context().getPropagationTags().createTagMap().get("_dd.p.dm") == "-0"

    cleanup:
    tracer.close()
  }

  void "LLMOBS+ASM: ASM-marked spans are kept with APPSEC mechanism"() {
    setup:
    def sampler = new StandaloneSampler([StandaloneProduct.LLMOBS, StandaloneProduct.ASM], Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "http-request").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    span.context().getPropagationTags().createTagMap().get("_dd.p.dm") == "-5"

    cleanup:
    tracer.close()
  }

  void "LLMOBS+ASM: span with both LLMOBS and ASM bits set is kept with DEFAULT mechanism (LLMOBS wins)"() {
    setup:
    def sampler = new StandaloneSampler([StandaloneProduct.LLMOBS, StandaloneProduct.ASM], Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "waf-llm-request").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.LLMOBS | ProductTraceSource.ASM)
    sampler.setSamplingPriority(span)
    scope.close()

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    span.context().getPropagationTags().createTagMap().get("_dd.p.dm") == "-0"

    cleanup:
    tracer.close()
  }

  void "LLMOBS+ASM: APM-only spans are rate-limited with APPSEC mechanism"() {
    setup:
    def current = new AtomicLong(System.currentTimeMillis())
    final Clock clock = Mock(Clock) {
      millis() >> {
        current.get()
      }
    }
    def sampler = new StandaloneSampler([StandaloneProduct.LLMOBS, StandaloneProduct.ASM], clock)
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
    1 * clock.millis() >> {
      current.updateAndGet(v -> v + 60000)
    }
    span3.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }

  void "sample() always returns true"() {
    setup:
    def sampler = new StandaloneSampler([StandaloneProduct.LLMOBS], Clock.systemUTC())
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "test").start()

    then:
    sampler.sample(span) == true

    cleanup:
    tracer.close()
  }
}
