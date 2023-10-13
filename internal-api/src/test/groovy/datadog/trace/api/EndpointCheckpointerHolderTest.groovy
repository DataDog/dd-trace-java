package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

class EndpointCheckpointerHolderTest extends DDSpecification {

  def "test sampling and fallback"() {
    setup:
    EndpointCheckpointer rootSpanCheckpointer = Mock()
    EndpointCheckpointerHolder sut = EndpointCheckpointerHolder.create()
    if (register) {
      sut.register(rootSpanCheckpointer)
    }
    long localRootSpanId = 1
    long spanId = 2
    String resource = "foo"
    AgentSpan rootSpan = Stub(AgentSpan)
    rootSpan.getSpanId() >> localRootSpanId
    rootSpan.getResourceName() >> UTF8BytesString.create(resource)

    AgentSpan span = Stub(AgentSpan)
    span.getSpanId() >> spanId
    span.getLocalRootSpan() >> rootSpan
    span.eligibleForDropping() >> drop
    int rootSpanCount = register ? 1 : 0

    when:
    def tracker = sut.onRootSpanStarted(rootSpan)
    then:
    rootSpanCount * rootSpanCheckpointer.onRootSpanStarted(rootSpan)

    when:
    sut.onRootSpanFinished(rootSpan, tracker)
    then:
    rootSpanCount * rootSpanCheckpointer.onRootSpanFinished(rootSpan, tracker)

    where:
    drop  | register
    true  | true
    true  | false
    false | true
    false | false
  }
}
