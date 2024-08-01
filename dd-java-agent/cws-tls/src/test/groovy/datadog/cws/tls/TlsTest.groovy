package datadog.cws.tls

import datadog.trace.api.DD128bTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class TlsTest extends DDSpecification {
  def "track span scopelistener"(){
    setup:
    DummyTls tls = new DummyTls()
    TlsScopeListener listener = new TlsScopeListener(tls)

    AgentSpan parent = Stub(AgentSpan)
    parent.getTraceId() >> DD128bTraceId.from(10L, 11L)
    parent.getSpanId() >> 12L

    AgentSpan span = Stub(AgentSpan)
    span.getTraceId() >> DD128bTraceId.from(20, 21L)
    span.getSpanId() >> 22L

    when:
    listener.afterScopeActivated(DD128bTraceId.from(10L, 11L), 12L)
    listener.afterScopeActivated(DD128bTraceId.from(20L, 21L), 22L)
    then:
    tls.getTraceId() == DD128bTraceId.from(20L, 21L)
    tls.getSpanId() == 22L

    when:
    listener.afterScopeClosed()
    then:
    tls.getTraceId() == DD128bTraceId.from(10L, 11L)
    tls.getSpanId() == 12L
  }
}
