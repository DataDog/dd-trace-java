package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.api.normalize.HttpResourceNames
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

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

  def "disambiguates identical paths across different hosts"() {
    setup:
    injectSysConfig(
      TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING,
      "host-a.com/oauth2/token:host_a_oauth,host-b.com/oauth2/token:host_b_oauth")

    AgentSpan spanA = tracer.startSpan("test", "test")
    AgentSpan spanB = tracer.startSpan("test", "test")

    when:
    decorator().withClientPath(spanA, "POST", "host-a.com", "/oauth2/token")
    decorator().withClientPath(spanB, "POST", "host-b.com", "/oauth2/token")

    then:
    spanA.resourceName.toString() == "POST host_a_oauth"
    spanB.resourceName.toString() == "POST host_b_oauth"
  }

  def "matches wildcard host in client path mapping"() {
    setup:
    injectSysConfig(
      TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING,
      "*.example.com/oauth2/token:example_oauth")

    AgentSpan span = tracer.startSpan("test", "test")

    when:
    decorator().withClientPath(span, "POST", "auth.example.com", "/oauth2/token")

    then:
    span.resourceName.toString() == "POST example_oauth"
  }

  def "interpolates {host} placeholder in client path mapping"() {
    setup:
    injectSysConfig(
      TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING,
      "/oauth2/token:{host}_oauth")

    AgentSpan span = tracer.startSpan("test", "test")

    when:
    decorator().withClientPath(span, "POST", "auth.provider-a.com", "/oauth2/token")

    then:
    span.resourceName.toString() == "POST auth.provider-a.com_oauth"
  }

  def "standard path-only mappings work when host is null or unmatched"() {
    setup:
    injectSysConfig(
      TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING,
      "/oauth2/token:oauth")

    AgentSpan spanNullHost = tracer.startSpan("test", "test")
    AgentSpan spanUnmatchedHost = tracer.startSpan("test", "test")

    when:
    decorator().withClientPath(spanNullHost, "POST", null, "/oauth2/token")
    decorator().withClientPath(spanUnmatchedHost, "POST", "unmatched-host.com", "/oauth2/token")

    then:
    spanNullHost.resourceName.toString() == "POST oauth"
    spanUnmatchedHost.resourceName.toString() == "POST oauth"
  }

  def "backward compatibility: 3-arg withClientPath delegates to null host"() {
    setup:
    injectSysConfig(
      TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING,
      "/oauth2/token:oauth")

    AgentSpan span = tracer.startSpan("test", "test")

    when:
    decorator().withClientPath(span, "POST", "/oauth2/token")

    then:
    span.resourceName.toString() == "POST oauth"
  }

  // Need a new one for every test after config injection
  private static HttpResourceDecorator decorator() {
    return new HttpResourceDecorator()
  }
}
