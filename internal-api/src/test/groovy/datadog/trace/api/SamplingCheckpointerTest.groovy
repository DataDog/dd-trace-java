package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END

class SamplingCheckpointerTest extends DDSpecification {

  def "test sampling and fallback"() {
    setup:
    Checkpointer checkpointer = Mock()
    EndpointCheckpointer rootSpanCheckpointer = Mock()
    SamplingCheckpointer sut = SamplingCheckpointer.create()
    if (register) {
      sut.register(checkpointer)
      sut.register(rootSpanCheckpointer)
    }
    long localRootSpanId = 1
    long spanId = 2
    String resource = "foo"
    AgentSpan rootSpan = Stub(AgentSpan)
    rootSpan.getSpanId() >> localRootSpanId
    rootSpan.getResourceName() >> UTF8BytesString.create(resource)
    rootSpan.isEmittingCheckpoints() >> emitCheckpoints

    AgentSpan span = Stub(AgentSpan)
    span.getSpanId() >> spanId
    span.getLocalRootSpan() >> rootSpan
    span.eligibleForDropping() >> drop
    int checkpointCount = register ? drop ? 0 : 1 : 0
    int rootSpanCount = register ? 1 : 0

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
    sut.checkpoint(span, CPU)
    then:
    checkpointCount * checkpointer.checkpoint(span, CPU)
    0 * _

    when:
    sut.onRootSpanStarted(rootSpan)
    then:
    rootSpanCount * rootSpanCheckpointer.onRootSpanStarted(rootSpan)

    when:
    sut.onRootSpanFinished(rootSpan, true)
    then:
    rootSpanCount * rootSpanCheckpointer.onRootSpanFinished(rootSpan, true)

    when:
    sut.onRootSpanFinished(rootSpan, false)
    then:
    rootSpanCount * rootSpanCheckpointer.onRootSpanFinished(rootSpan, false)

    where:
    drop  | register | emitCheckpoints
    true  | true     | true
    true  | true     | false
    true  | false    | true
    true  | false    | false
    false | true     | true
    false | true     | false
    false | false    | true
    false | false    | false
  }
}
