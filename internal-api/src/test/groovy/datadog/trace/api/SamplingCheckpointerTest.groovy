package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
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
    AgentSpan span = Stub(AgentSpan)
    span.eligibleForDropping() >> drop
    span.getTraceId() >> traceId
    span.getSpanId() >> spanId
    int count = register ? drop ? 0 : 1 : 0

    when:
    sut.onStart(span)
    then:
    count * checkpointer.checkpoint(traceId, spanId, SPAN)
    0 * _

    when:
    sut.onCommenceWork(span)
    then:
    count * checkpointer.checkpoint(traceId, spanId, CPU)
    0 * _

    when:
    sut.onCompleteWork(span)
    then:
    count * checkpointer.checkpoint(traceId, spanId, CPU | END)
    0 * _

    when:
    sut.onThreadMigration(span)
    then:
    count * checkpointer.checkpoint(traceId, spanId, THREAD_MIGRATION)
    0 * _

    when:
    sut.onAsyncResume(span)
    then:
    count * checkpointer.checkpoint(traceId, spanId, THREAD_MIGRATION | END)
    0 * _

    when:
    sut.onComplexEvent(span, CPU | SPAN)
    then:
    count * checkpointer.checkpoint(traceId, spanId, CPU | SPAN)
    0 * _

    when:
    sut.onFinish(span)
    then:
    count * checkpointer.checkpoint(traceId, spanId, SPAN | END)
    0 * _

    where:
    drop | register
    true | true
    true | false
    false | true
    false | false
  }
}
