package datadog.trace.api.http

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.http.RouteHandlerDecorator.ROUTE_HANDLER_DECORATOR

class RouteHandlerDecoratorTest extends DDSpecification {

  def "test that resource name is not changed"() {
    given:
    injectSysConfig("http.server.route-based-naming", "false")

    AgentSpan span = Mock(AgentSpan)

    when:
    ROUTE_HANDLER_DECORATOR.withRoute(span, "GET", "/not-the-resource-name")

    then:
    0 * span.setResourceName(_)
  }
}
