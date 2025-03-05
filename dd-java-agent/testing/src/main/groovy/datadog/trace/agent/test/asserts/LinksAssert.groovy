package datadog.trace.agent.test.asserts

import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes
import datadog.trace.bootstrap.instrumentation.api.SpanLink
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

class LinksAssert {
  private final List<AgentSpanLink> links
  private final Set<AgentSpanLink> assertedLinks = []

  private LinksAssert(DDSpan span) {
    this.links = span.links // this is class protected but for the moment groovy can access it
  }

  static void assertLinks(DDSpan span,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.LinksAssert'])
    @DelegatesTo(value = LinksAssert, strategy = Closure.DELEGATE_FIRST) Closure spec,
    boolean checkAllLinks = true) {
    def asserter = new LinksAssert(span)
    def clone = (Closure) spec.clone()
    clone.delegate = asserter
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(asserter)
    if (checkAllLinks) {
      asserter.assertLinksAllVerified()
    }
  }

  def link(DDSpan linked, byte flags = SpanLink.DEFAULT_FLAGS, SpanAttributes attributes = SpanAttributes.EMPTY, String traceState = '') {
    link(linked.context(), flags, attributes, traceState)
  }

  def link(AgentSpanContext context, byte flags = SpanLink.DEFAULT_FLAGS, SpanAttributes attributes = SpanAttributes.EMPTY, String traceState = '') {
    link(context.traceId, context.spanId, flags, attributes, traceState)
  }

  def link(DDTraceId traceId, long spanId, byte flags = SpanLink.DEFAULT_FLAGS, SpanAttributes attributes = SpanAttributes.EMPTY, String traceState = '') {
    def found = links.find {
      it.spanId() == spanId && it.traceId() == traceId
    }
    assert found != null
    assert found.traceFlags() == flags
    assert found.attributes() == attributes
    assert found.traceState() == traceState
    assertedLinks.add(found)
  }

  void assertLinksAllVerified() {
    def list = new ArrayList(links)
    list.removeAll(assertedLinks)
    assert list.isEmpty()
  }
}
