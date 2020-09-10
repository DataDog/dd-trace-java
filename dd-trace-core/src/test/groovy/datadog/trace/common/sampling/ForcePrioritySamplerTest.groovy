package datadog.trace.common.sampling

import datadog.trace.api.DDTags
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.SpanFactory
import datadog.trace.util.test.DDSpecification

class ForcePrioritySamplerTest extends DDSpecification {

  def "force priority sampling"() {
    setup:
    def sampler = new ForcePrioritySampler(prioritySampling)

    when:
    DDSpan span1 = SpanFactory.newSpanOf("foo", "bar")
    sampler.setSamplingPriority(span1)

    then:
    span1.getSamplingPriority() == expectedSampling
    sampler.sample(span1)

    where:
    prioritySampling              | expectedSampling
    PrioritySampling.SAMPLER_KEEP | PrioritySampling.SAMPLER_KEEP
    PrioritySampling.SAMPLER_DROP | PrioritySampling.SAMPLER_DROP
  }

  def "sampling priority set"() {
    setup:
    def sampler = new ForcePrioritySampler(prioritySampling)
    def tracer = CoreTracer.builder().writer(new LoggingWriter()).sampler(sampler).build()

    when:
    def span = tracer.buildSpan("test").start()

    then:
    span.getSamplingPriority() == null

    when:
    span.setTag(DDTags.SERVICE_NAME, "spock")

    then:
    span.finish()
    span.getSamplingPriority() == expectedSampling

    where:
    prioritySampling              | expectedSampling
    PrioritySampling.SAMPLER_KEEP | PrioritySampling.SAMPLER_KEEP
    PrioritySampling.SAMPLER_DROP | PrioritySampling.SAMPLER_DROP
  }

  def "setting forced tracing via tag"() {
    when:
    def sampler = new ForcePrioritySampler(PrioritySampling.SAMPLER_KEEP)
    def tracer = CoreTracer.builder().writer(new LoggingWriter()).sampler(sampler).build()
    def span = tracer.buildSpan("root").start()
    if (tagName) {
      span.setTag(tagName, tagValue)
    }
    span.finish()

    then:
    span.getSamplingPriority() == expectedPriority

    where:
    tagName       | tagValue | expectedPriority
    'manual.drop' | true     | PrioritySampling.USER_DROP
    'manual.keep' | true     | PrioritySampling.USER_KEEP
  }

  def "not setting forced tracing via tag or setting it wrong value not causing exception"() {
    setup:
    def sampler = new ForcePrioritySampler(PrioritySampling.SAMPLER_KEEP)
    def tracer = CoreTracer.builder().writer(new LoggingWriter()).sampler(sampler).build()
    def span = tracer.buildSpan("root").start()
    if (tagName) {
      span.setTag(tagName, tagValue)
    }

    expect:
    span.getSamplingPriority() == null

    cleanup:
    span.finish()

    where:
    tagName       | tagValue
    // When no tag is set default to
    null          | null
    // Setting to not known value
    'manual.drop' | false
    'manual.keep' | false
    'manual.drop' | 1
    'manual.keep' | 1
  }
}
