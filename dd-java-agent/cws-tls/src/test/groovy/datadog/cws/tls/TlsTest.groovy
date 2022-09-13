package datadog.cws.tls

import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class TlsTest extends DDSpecification {
  def "track span scopelistener"(){
    setup:
    DummyTls tls = new DummyTls()
    TlsScopeListener listener = new TlsScopeListener(tls)

    AgentSpan parent = Stub(AgentSpan)
    parent.getTraceId() >> DDId.from(11L)
    parent.getSpanId() >> DDId.from(12L)

    AgentSpan span = Stub(AgentSpan)
    span.getTraceId() >> DDId.from(21L)
    span.getSpanId() >> DDId.from(22L)

    when:
    listener.afterScopeActivated(DDId.from(11L), DDId.from(12L))
    listener.afterScopeActivated(DDId.from(21L), DDId.from(22L))
    then:
    tls.getTraceId() == DDId.from(21L)
    tls.getSpanId() == DDId.from(22L)

    when:
    listener.afterScopeClosed()
    then:
    tls.getTraceId() == DDId.from(11L)
    tls.getSpanId() == DDId.from(12L)
  }
}