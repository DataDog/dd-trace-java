package datadog.trace.bootstrap.instrumentation.jms

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class SessionStateTest extends DDSpecification {

  def "commit transaction"() {
    setup:
    SessionState sessionState = new SessionState()
    def span1 = Mock(AgentSpan)
    def span2 = Mock(AgentSpan)
    when:
    sessionState.add(span1)
    sessionState.add(span2)
    then:
    0 * span1.finish()
    0 * span2.finish()
    when: "transaction committed"
    sessionState.onCommit()
    then: "spans finished and queues empty"
    1 * span1.finish()
    1 * span2.finish()
    sessionState.isEmpty()
  }

  def "when buffer overflows, spans are finished eagerly"() {
    setup:
    SessionState sessionState = new SessionState()
    AgentSpan span1 = Mock(AgentSpan)
    AgentSpan span2 = Mock(AgentSpan)
    when: "fill the buffer"
    for (int i = 0; i < SessionState.CAPACITY; ++i) {
      sessionState.add(span1)
    }
    then: "spans are not finished on entry"
    0 * span1.finish()
    when: "buffer overflows"
    sessionState.add(span2)
    then: "span is finished on entry"
    1 * span2.finish()
    when: "commit and add span"
    sessionState.onCommit()
    sessionState.add(span2)
    then: "span is enqueued and not finished"
    0 * span2.finish()
  }
}
