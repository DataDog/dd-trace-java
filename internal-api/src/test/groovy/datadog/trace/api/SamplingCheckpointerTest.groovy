package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION

class SamplingCheckpointerTest extends DDSpecification {

  def "test sampling and fallback"() {
    setup:
    Checkpointer checkpointer = Mock()
    SamplingCheckpointer sut = SamplingCheckpointer.create()
    if (register) {
      sut.register(checkpointer)
    }
    DDId traceId = DDId.from(1)
    DDId spanId = DDId.from(2)
    String resource = "foo"
    AgentSpan span = Stub(AgentSpan)
    span.eligibleForDropping() >> drop
    span.getTraceId() >> traceId
    span.getSpanId() >> spanId
    span.getResourceName() >> UTF8BytesString.create(resource)
    span.isEmittingCheckpoints() >> true
    int checkpointCount = register ? drop ? 0 : 1 : 0
    int rootSpanCount = register ? 1 : 0

    when:
    sut.onStart(span)
    then:
    checkpointCount * checkpointer.checkpoint(span, SPAN)
    0 * _

    when:
    sut.onStartWork(span)
    then:
    checkpointCount * checkpointer.checkpoint(span, CPU)
    0 * _

    when:
    sut.onFinishWork(span)
    then:
    checkpointCount * checkpointer.checkpoint(span, CPU | END)
    0 * _

    when:
    sut.onStartThreadMigration(span)
    then:
    checkpointCount * checkpointer.checkpoint(span, THREAD_MIGRATION)
    0 * _

    when:
    sut.onFinishThreadMigration(span)
    then:
    checkpointCount * checkpointer.checkpoint(span, THREAD_MIGRATION | END)
    0 * _

    when:
    sut.checkpoint(span, CPU | SPAN)
    then:
    checkpointCount * checkpointer.checkpoint(span, CPU | SPAN)
    0 * _

    when:
    sut.onFinish(span)
    then:
    checkpointCount * checkpointer.checkpoint(span, SPAN | END)
    0 * _

    when:
    sut.onRootSpan(span, true)
    then:
    rootSpanCount * checkpointer.onRootSpan(resource, traceId, true)

    when:
    sut.onRootSpan(span, false)
    then:
    rootSpanCount * checkpointer.onRootSpan(resource, traceId, false)

    where:
    drop | register
    true | true
    true | false
    false | true
    false | false
  }
}
