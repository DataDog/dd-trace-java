package datadog.trace.core.scopemanager

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.context.ScopeListener
import datadog.trace.util.test.DDSpecification
import spock.lang.Shared

class ListenerScopeInterceptorTest extends DDSpecification {
  @Shared
  def listener = Mock(ScopeListener)

  def delegate = Mock(ScopeInterceptor)
  def delegateScope = Mock(ScopeInterceptor.Scope)

  def span = Mock(AgentSpan)

  def "validate scope lifecycle"() {
    setup:
    def interceptor = new ListenerScopeInterceptor(listeners, delegate)

    when:
    def scope = interceptor.handleSpan(span)

    then:
    1 * delegate.handleSpan(span) >> delegateScope
    0 * _

    when:
    scope.afterActivated()

    then:
    1 * delegateScope.afterActivated()
    0 * _

    when:
    scope.close()

    then:
    1 * delegateScope.close()
    0 * _

    where:
    listeners << [[], [listener]]
    hasListener = listeners.isEmpty()
  }
}
