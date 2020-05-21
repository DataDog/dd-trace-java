package datadog.trace.core.scopemanager


import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.core.jfr.DDScopeEvent
import datadog.trace.core.jfr.DDScopeEventFactory
import datadog.trace.util.test.DDSpecification
import spock.lang.Subject

class EventScopeInterceptorTest extends DDSpecification {
  def eventFactory = Mock(DDScopeEventFactory)
  def delegateScope = Mock(ScopeInterceptor.Scope)
  def delegate = Mock(ScopeInterceptor)

  def span = Mock(AgentSpan)
  def context = Mock(AgentSpan.Context)

  @Subject
  def interceptor = new EventScopeInterceptor(eventFactory, delegate)

  def "validate scope lifecycle"() {
    setup:
    def event = Mock(DDScopeEvent)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    1 * delegate.handleSpan(span) >> delegateScope
    1 * span.context() >> context
    1 * eventFactory.create(context) >> event
    0 * _

    when:
    scope.afterActivated()

    then:
    1 * delegateScope.afterActivated()
    1 * event.start()
    0 * _

    when:
    scope.close()

    then:
    1 * delegateScope.close()
    1 * event.finish()
    0 * _
  }
}
