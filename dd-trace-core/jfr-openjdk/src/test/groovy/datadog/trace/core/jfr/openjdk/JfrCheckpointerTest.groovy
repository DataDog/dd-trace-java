package datadog.trace.core.jfr.openjdk

import datadog.trace.api.DDId
import datadog.trace.api.sampling.AdaptiveSampler
import datadog.trace.bootstrap.config.provider.ConfigProvider
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

class JfrCheckpointerTest extends DDSpecification {
  def "test span checkpoint sample"() {
    setup:
    AdaptiveSampler sampler = stubbedSampler(sampled)

    JFRCheckpointer checkpointer = Spy(new JFRCheckpointer(sampler, ConfigProvider.getInstance()))
    checkpointer.emitCheckpoint(_, _)  >> {}
    checkpointer.dropCheckpoint() >> {}

    AgentSpan span = mockSpan(checkpointed)

    when:
    checkpointer.checkpoint(span, 0)
    then:
    setEmitting * span.setEmittingCheckpoints(true)
    setDropping * span.setEmittingCheckpoints(false)
    checkpoints * checkpointer.emitCheckpoint(span.traceId, span.spanId, 0)
    (1 - checkpoints) * checkpointer.dropCheckpoint()

    where:
    checkpointed  | sampled   | checkpoints   | setEmitting | setDropping
    null          | true      | 1             | 1           | 0
    null          | false     | 0             | 0           | 1
    true          | true      | 1             | 0           | 0
    true          | false     | 1             | 0           | 0
    false         | true      | 0             | 0           | 0
    false         | false     | 0             | 0           | 0
  }

  def stubbedSampler(def sampled) {
    AdaptiveSampler sampler = Stub(AdaptiveSampler)
    sampler.drop() >> false
    sampler.keep() >> true
    sampler.sample() >> sampled
    return sampler
  }

  private static spanId = 1L
  private static traceId = 1L
  def mockSpan(def checkpointed, def dropping = false, def resource = "foo") {
    DDId traceId = DDId.from(traceId++)
    DDId spanId = DDId.from(spanId++)

    def span = Mock(AgentSpan)
    span.eligibleForDropping() >> dropping
    span.getTraceId() >> traceId
    span.getSpanId() >> spanId
    span.getResourceName() >> UTF8BytesString.create(resource)
    span.isEmittingCheckpoints() >> checkpointed

    return span
  }
}
