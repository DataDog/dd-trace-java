package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

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
    AgentSpan span = tracer.startSpan("test")

    when:
    def scope = AgentTracer.activateSpan(span)
    decorator().withRoute(span, "GET", "/not-the-resource-name")
    scope.close()
    span.finish()

    then:
    span.resourceName == "test"
  }

  def "still uses the simple normalizer by default"() {
    given:
    AgentSpan span = tracer.startSpan("test")
    String method = "GET"
    String path = "/asdf/1234"

    when:
    decorator().withServerPath(span, method, path, false)

    then:
    span.resourceName.toString() == "GET /asdf/?"
  }

  def "uses the ant matching normalizer when configured to"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")

    AgentSpan span = tracer.startSpan("test")
    String method = "GET"
    String path = "/asdf/1234"

    when:
    decorator().withServerPath(span, method, path, false)

    then:
    span.resourceName.toString() == "GET /test"
  }

  def "falls back to simple normalizer"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")

    AgentSpan span = tracer.startSpan("test")
    String method = "GET"
    String path = "/unknown/1234"

    when:
    decorator().withServerPath(span, method, path, false)

    then:
    span.resourceName.toString() == "GET /unknown/?"
  }

  def "returns sane default when disabled"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/a/*:/test")
    injectSysConfig("trace.URLAsResourceNameRule.enabled", "false")

    AgentSpan span = tracer.startSpan("test")
    String method = "GET"
    String path = "/unknown/1234"

    when:
    decorator().withServerPath(span, method, path, false)

    then:
    span.resourceName.toString() == "/"
  }

  // Need a new one for every test after config injection
  private static HttpResourceDecorator decorator() {
    return new HttpResourceDecorator()
  }
}
