package datadog.trace.common.sampling

import datadog.trace.api.DDTraceId
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.RemoteResponseListener
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP

class ParentBasedAlwaysOnSamplerTest extends DDCoreSpecification {

  def writer = new ListWriter()

  def "always samples spans"() {
    setup:
    def sampler = new ParentBasedAlwaysOnSampler()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("test").start()

    then:
    sampler.sample(span) == true

    cleanup:
    span.finish()
    tracer.close()
  }

  def "sets sampling priority to SAMPLER_KEEP"() {
    setup:
    def sampler = new ParentBasedAlwaysOnSampler()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("test").start()
    sampler.setSamplingPriority(span)

    then:
    span.getSamplingPriority() == SAMPLER_KEEP

    cleanup:
    span.finish()
    tracer.close()
  }

  def "child span inherits sampling priority from local parent"() {
    setup:
    def sampler = new ParentBasedAlwaysOnSampler()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    when:
    def rootSpan = tracer.buildSpan("root").start()
    sampler.setSamplingPriority(rootSpan)
    def childSpan = tracer.buildSpan("child").asChildOf(rootSpan).start()

    then:
    rootSpan.getSamplingPriority() == SAMPLER_KEEP
    childSpan.getSamplingPriority() == SAMPLER_KEEP

    cleanup:
    childSpan.finish()
    rootSpan.finish()
    tracer.close()
  }

  def "child span inherits sampling decision from remote parent"() {
    setup:
    def sampler = new ParentBasedAlwaysOnSampler()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()
    def extractedContext = new ExtractedContext(
      DDTraceId.ONE, 2, parentPriority, null,
      PropagationTags.factory().empty(), DATADOG)

    when:
    def span = tracer.buildSpan("child").asChildOf(extractedContext).start()

    then:
    span.getSamplingPriority() == parentPriority

    cleanup:
    span.finish()
    tracer.close()

    where:
    parentPriority | _
    SAMPLER_KEEP   | _
    SAMPLER_DROP   | _
    USER_KEEP      | _
    USER_DROP      | _
  }

  def "is not a RemoteResponseListener"() {
    setup:
    def sampler = new ParentBasedAlwaysOnSampler()

    expect:
    !(sampler instanceof RemoteResponseListener)
  }

  def "implements PrioritySampler"() {
    setup:
    def sampler = new ParentBasedAlwaysOnSampler()

    expect:
    sampler instanceof PrioritySampler
  }
}
