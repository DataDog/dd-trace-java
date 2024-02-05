package datadog.trace.core

import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.ScopedContextKey
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class DDScopedContextTest extends DDSpecification {
  @Shared
  def span = AgentTracer.NoopAgentSpan.INSTANCE
  @Shared
  def baggage = DDBaggage.builder().put("key1", "value1").build()
  @Shared
  def someObject = new Object()
  @Shared
  def someObjectKey = ScopedContextKey.named("someObject")

  def "check immutability and inheritance"() {
    when:
    def context0 = DDScopedContext.empty()

    then:
    context0.span() == null
    context0.baggage() == DDBaggage.empty()

    when:

    def context1 = context0.with(baggage)
    def context2 = context1.with(span)
    def context3 = context2.with(someObjectKey, someObject)

    then:
    context0.span() == null
    context0.baggage() == DDBaggage.empty()
    context0.get(someObjectKey) == null
    context1.span() == null
    context1.baggage() == baggage
    context1.get(someObjectKey) == null
    context2.span() == span
    context2.baggage() == baggage
    context2.get(someObjectKey) == null
    context3.span() == span
    context3.baggage() == baggage
    context3.get(someObjectKey) == someObject
  }

  def "check storage"() {
    setup:
    def anotherObject = new Object()
    def anotherObjectKey = ScopedContextKey.named("anotherObject")
    def someObjectNewValue = "String"

    when:
    def context = DDScopedContext.empty()
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

  def "check equals/hashCode"() {
    when:
    def context0 = DDScopedContext.empty()
    def context1 = context0.with(someObjectKey, someObject)
    def context2 = context1.with(someObjectKey, "String")
    def context3 = context2.with(someObjectKey, someObject)

    then:
    context0 != context1
    context1 != context2
    context2 != context3
    context3 == context1

    and:
    context0.hashCode() != context1.hashCode()
    context1.hashCode() != context2.hashCode()
    context2.hashCode() != context3.hashCode()
    context3.hashCode() == context1.hashCode()
  }
}
