package datadog.cws.tls

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class TlsTest extends DDSpecification {
  def "track span scopelistener"(){
    setup:
    DummyTls tls = new DummyTls()
    TlsScopeListener listener = new TlsScopeListener(tls)

    AgentSpan parent = Stub(AgentSpan)
    parent.getTraceId() >> DDTraceId.from(11L)
    parent.getSpanId() >> 12L

    AgentSpan span = Stub(AgentSpan)
    span.getTraceId() >> DDTraceId.from(21L)
    span.getSpanId() >> 22L

    when:
    listener.afterScopeActivated(DDTraceId.from(11L), DDSpanId.ZERO, 12L, null)
    listener.afterScopeActivated(DDTraceId.from(21L), DDSpanId.ZERO, 22L, null)
    then:
    tls.getTraceId() == DDTraceId.from(21L)
    tls.getSpanId() == 22L

    when:
    listener.afterScopeClosed()
    then:
    tls.getTraceId() == DDTraceId.from(11L)
    tls.getSpanId() == 12L
  }
}
