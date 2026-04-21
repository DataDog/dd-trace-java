package datadog.trace.core

import datadog.trace.api.ProductTraceSource
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.sampling.StandaloneProduct
import datadog.trace.common.sampling.StandaloneSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification

import java.time.Clock

class TraceCollectorTest extends DDCoreSpecification {

  def writer = new ListWriter()

  void "setSamplingPriorityIfNecessary: sampler is skipped when APM disabled, standalone product flag set, and priority already non-UNSET"() {
    setup:
    injectSysConfig("dd.apm.tracing.enabled", "false")
    def sampler = Spy(StandaloneSampler, constructorArgs: [[StandaloneProduct.LLMOBS], Clock.systemUTC()])
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("testInstrumentation", "llm-request").start()
    def scope = tracer.activateSpan(span)
    tracer.getTraceSegment().setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.LLMOBS)
    span.setSamplingPriority(PrioritySampling.USER_KEEP, SamplingMechanism.MANUAL)
    scope.close()
    span.finish()
    writer.waitForTraces(1)

    then:
    0 * sampler.setSamplingPriority(_)
    span.getSamplingPriority() == PrioritySampling.USER_KEEP

    cleanup:
    tracer.close()
  }
}
