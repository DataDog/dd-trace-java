package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.api.normalize.HttpResourceNames
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT

class HttpResourceDecoratorTest extends DDSpecification {

  @Shared
  CoreTracer tracer = CoreTracer.builder().build()

  def setup() {
    injectSysConfig("http.server.route-based-naming", "false")
    HttpResourceNames.INSTANCE = null
  }

  def cleanupSpec() {
    tracer.close()
  }

  def "test that resource name is not changed"() {
    given:
    AgentSpan span = tracer.startSpan("test", "test")

    when:
    def scope = AgentTracer.activateSpan(span)
    decorator().withRoute(span, "GET", "/not-the-resource-name")
    scope.close()
    span.finish()

    then:
    span.resourceName == "test"
  }

  def "prefixes route with servlet context '#contextPath'"() {
    given:
    AgentSpan span = tracer.startSpan("test", "test")
    if (contextPath != null) {
      span.setTag(SERVLET_CONTEXT, contextPath)
    }

    when:
    decorator().withRoute(span, "GET", route)

    then:
    span.getTag(Tags.HTTP_ROUTE).toString() == expectedRoute
    (span.getTag(Tags.HTTP_ROUTE).is(route)) == sameReference

    where:
    contextPath | route         | expectedRoute         | sameReference
    null        | "/save"       | "/save"               | true
    ""          | "/save"       | "/save"               | true
    "/"         | "/save"       | "/save"               | true
    "/cache"    | "/save"       | "/cache/save"         | false
    "/cache"    | "/cache/save" | "/cache/cache/save"   | false
    "/cache"    | "/items/{id}" | "/cache/items/{id}"   | false
  }

  def "uses servlet context in route-based resource name"() {
    given:
    injectSysConfig("http.server.route-based-naming", "true")
    AgentSpan span = tracer.startSpan("test", "test")
    span.setTag(SERVLET_CONTEXT, "/application")

    when:
    decorator().withRoute(span, "GET", "/items/{id}")

    then:
    span.getTag(Tags.HTTP_ROUTE) == "/application/items/{id}"
    span.resourceName.toString() == "GET /application/items/{id}"
  }

  def "preserves encoded resource naming while prefixing servlet context"() {
    given:
    injectSysConfig("http.server.route-based-naming", "true")
    AgentSpan span = tracer.startSpan("test", "test")
    span.setTag(SERVLET_CONTEXT, "/application")

    when:
    decorator().withRoute(span, "GET", "/items%20list", true)

    then:
    span.getTag(Tags.HTTP_ROUTE) == "/application/items list"
    span.resourceName.toString() == "GET /application/items%20list"
  }

  def "still uses the simple normalizer by default"() {
    given:
    AgentSpan span = tracer.startSpan("test", "test")
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

    AgentSpan span = tracer.startSpan("test", "test")
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

    AgentSpan span = tracer.startSpan("test", "test")
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

    AgentSpan span = tracer.startSpan("test", "test")
    String method = "GET"
    String path = "/unknown/1234"

    when:
    decorator().withServerPath(span, method, path, false)

    then:
    span.resourceName.toString() == "/"
  }

  def "returns mapped client path"() {
    setup:
    injectSysConfig(TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, "/a/*:/test")

    AgentSpan span = tracer.startSpan("test", "test")

    when:
    decorator().withClientPath(span, "GET", "/a/foo")

    then:
    span.resourceName.toString() == "GET /test"
  }

  def "returns original client path"() {
    setup:
    injectSysConfig(TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, "/a/*:*")

    AgentSpan span = tracer.startSpan("test", "test")

    when:
    decorator().withClientPath(span, "GET", "/a/foo")

    then:
    span.resourceName.toString() == "GET /a/foo"
  }

  // Need a new one for every test after config injection
  private static HttpResourceDecorator decorator() {
    return new HttpResourceDecorator()
  }
}
