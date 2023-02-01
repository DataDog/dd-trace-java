package datadog.trace.core.scopemanager

import datadog.trace.core.DDBaggage
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

// NOTE:
// ScopeContext is not generic yet.
// It only supports span and baggage for now.
// That requires to have a span that to be the an instance of DDSpan and have the same context key.
class ScopeContextTest extends DDSpecification {
  @Shared
  def span = buildSpan()
  @Shared
  def baggage = DDBaggage.builder().put("key1", "value1").build()

  def buildSpan() {
    def span = Mock(DDSpan)
    span.contextKey() >> "dd-span-key"
    return span
  }

  def "check immutability and inheritance"() {
    when:
    def empty = ScopeContext.empty()

    then:
    empty.span() == null
    empty.baggage() == null

    when:

    def context1 = empty.with(baggage)
    def context2 = context1.with(span)

    then:
    empty.span() == null
    empty.baggage() == null
    context1.span() == null
    context1.baggage() == baggage
    context2.span() == span
    context2.baggage() == baggage
  }

  def "get element"() {
    when:
    def context = ScopeContext.empty().with(element)

    then:
    context.getElement(key) == expected

    where:
    element | key                  | expected
    null    | null                 | null
    span    | null                 | null
    span    | span.contextKey()    | span
    baggage | null                 | null
    baggage | baggage.contextKey() | baggage
  }

  def "check equals"() {
    when:
    def context1 = ScopeContext.empty().with(this.span)
    def context2 = ScopeContext.fromSpan(this.span)

    then:
    context1 == context2
  }
}
