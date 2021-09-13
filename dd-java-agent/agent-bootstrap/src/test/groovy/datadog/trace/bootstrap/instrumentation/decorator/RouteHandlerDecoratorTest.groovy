package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.bootstrap.instrumentation.decorator.RouteHandlerDecorator.ROUTE_HANDLER_DECORATOR

class RouteHandlerDecoratorTest extends DDSpecification {

  @Shared
  CoreTracer tracer

  def setup() {
    injectSysConfig("http.server.route-based-naming", "false")

    tracer = CoreTracer.builder().build()
  }

  def cleanup() {
    tracer.close()
  }

  def "test that resource name is not changed"() {
    given:
    AgentSpan span = tracer.startSpan("test", true)

    when:
    def scope = AgentTracer.activateSpan(span)
    ROUTE_HANDLER_DECORATOR.withRoute(span, "GET", "/not-the-resource-name")
    scope.close()
    span.finish()

    then:
    span.resourceName == "test"
  }
}
