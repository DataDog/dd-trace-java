package datadog.cws.tls

import datadog.context.Context
import datadog.trace.api.DD128bTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.tracing.ContextKeys.SPAN_CONTEXT_KEY

class TlsTest extends DDSpecification {
  def "track span scopelistener"(){
    setup:
    DummyTls tls = new DummyTls()
    TlsContextListener listener = new TlsContextListener(tls)
    def empty = Context.empty()

    AgentSpan parent = Stub(AgentSpan)
    def parentTraceId = DD128bTraceId.from(10L, 11L)
    def parentSpanId = 12L
    parent.getTraceId() >> parentTraceId
    parent.getSpanId() >> parentSpanId
    def parentContext = empty.with(SPAN_CONTEXT_KEY, parent)

    AgentSpan span = Stub(AgentSpan)
    def spanTraceId = DD128bTraceId.from(20, 21L)
    def spanId = 22L
    span.getTraceId() >> spanTraceId
    span.getSpanId() >> spanId
    def spanContext = empty.with(SPAN_CONTEXT_KEY, span)

    when:
    listener.onAttached(empty, parentContext)
    listener.onAttached(parentContext, spanContext)
    then:
    tls.getTraceId() == DD128bTraceId.from(20L, 21L)
    tls.getSpanId() == 22L

    when:
    listener.onAttached(spanContext, parentContext)
    then:
    tls.getTraceId() == DD128bTraceId.from(10L, 11L)
    tls.getSpanId() == 12L
  }
}
