package datadog.trace.core.scopemanager

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.ContextKey
import datadog.trace.core.DDBaggage
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.SPAN_CONTEXT_KEY
import static datadog.trace.core.scopemanager.ScopeContext.BAGGAGE

class ScopeContextTest extends DDSpecification {
  @Shared
  def span = Mock(AgentSpan)
  @Shared
  def baggage = DDBaggage.builder().put("key1", "value1").build()
  @Shared
  def someObject = new Object()
  @Shared
  def someObjectKey = ContextKey.<Object>named("someObject")

  def "check immutability and inheritance"() {
    when:
    def empty = ScopeContext.empty()

    then:
    empty.span() == null
    empty.baggage() == null

    when:

    def context1 = empty.with(BAGGAGE, baggage)
    def context2 = context1.with(SPAN_CONTEXT_KEY, span)

    then:
    empty.span() == null
    empty.baggage() == null
    context1.span() == null
    context1.baggage() == baggage
    context2.span() == span
    context2.baggage() == baggage
  }

  def "check storage"() {
    when:
    def context = ScopeContext.empty().with(key, element)

    then:
    context.get(key) == expected

    where:
    element    | key                 | expected
    null       | null                | null
    span       | null                | null
    span       | SPAN_CONTEXT_KEY    | span
    null       | SPAN_CONTEXT_KEY    | null
    baggage    | BAGGAGE             | baggage
    someObject | someObjectKey       | someObject
  }

  def "check generic storage"() {
    setup:
    def anotherObject = new Object()
    def anotherObjectKey = ContextKey.<Object>named("anotherObject")
    def someObjectNewValue = "String"

    when:
    def context = ScopeContext.empty()
    then:
    context.get(someObjectKey) == null
    context.get(anotherObjectKey) == null

    when:
    context = context.with(someObjectKey, someObject)
    then:
    context.get(someObjectKey) == someObject
    context.get(anotherObjectKey) == null

    when:
    context = context.with(anotherObjectKey, anotherObject)
    then:
    context.get(someObjectKey) == someObject
    context.get(anotherObjectKey) == anotherObject

    when:
    context = context.with(someObjectKey, someObjectNewValue)
    then:
    context.get(someObjectKey) == someObjectNewValue
    context.get(anotherObjectKey) == anotherObject
  }

  def "check fromSpan"() {
    when:
    def context = ScopeContext.fromSpan(this.span)

    then:
    context.span() == this.span
  }
}
