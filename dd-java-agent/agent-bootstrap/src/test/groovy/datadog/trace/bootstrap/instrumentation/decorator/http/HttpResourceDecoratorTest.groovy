package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static HttpResourceDecorator.HTTP_RESOURCE_DECORATOR

class HttpResourceDecoratorTest extends DDSpecification {

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
    HTTP_RESOURCE_DECORATOR.withRoute(span, "GET", "/not-the-resource-name")
    scope.close()
    span.finish()

    then:
    span.resourceName == "test"
  }
}
