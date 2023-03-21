package datadog.trace.api.normalize

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

class HttpResourceNamesTest extends DDSpecification {
  def setup() {
    HttpResourceNames.INSTANCE = null
    HttpResourceNames.JOINER_CACHE.clear()
  }

  def "uses the simple normalizer for servers by default"() {
    given:
    def span = Mock(AgentSpan)

    when:
    HttpResourceNames.setForServer(span, "GET", "/asdf/1234", false)

    then:
    1 * span.setResourceName(UTF8BytesString.create("GET /asdf/?"), ResourceNamePriorities.HTTP_PATH_NORMALIZER)
  }

  def "uses the ant matching normalizer for servers when configured to"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")
    def span = Mock(AgentSpan)

    when:
    HttpResourceNames.setForServer(span, "GET", "/asdf/1234", false)

    then:
    1 * span.setResourceName(UTF8BytesString.create("GET /test"), ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH)
  }

  def "uses the simple normalizer for clients by default"() {
    given:
    def span = Mock(AgentSpan)

    when:
    HttpResourceNames.setForClient(span, "GET", "/asdf/1234", false)

    then:
    1 * span.setResourceName(UTF8BytesString.create("GET /asdf/?"), ResourceNamePriorities.HTTP_PATH_NORMALIZER)
  }

  def "uses the ant matching normalizer for clients when configured to"() {
    setup:
    injectSysConfig(TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")
    def span = Mock(AgentSpan)

    when:
    HttpResourceNames.setForClient(span, "GET", "/asdf/1234", false)

    then:
    1 * span.setResourceName(UTF8BytesString.create("GET /test"), ResourceNamePriorities.HTTP_CLIENT_CONFIG_PATTERN_MATCH)
  }

  def "works as expected"() {
    when:
    def resourceName = HttpResourceNames.join(method, path)

    then:
    resourceName.toString() == expected

    where:
    method | path        | expected
    "GET"  | "/test"     | "GET /test"
    null   | "/test"     | "/test"
    "GET"  | null        | "/"
    "GET"  | ""          | "GET "
    "GET"  | "/"         | "GET /"
    "GET"  | "//"        | "GET //"
    "GET"  | "/foo/bar"  | "GET /foo/bar"
    "GET"  | "/foo/bar/" | "GET /foo/bar/"
  }

  def "remove trailing slash"() {
    setup:
    injectSysConfig(TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH, "true")

    when:
    def resourceName = HttpResourceNames.join(method, path)

    then:
    resourceName.toString() == expected

    where:
    method | path        | expected
    "GET"  | "/foo/bar/" | "GET /foo/bar"
    "GET"  | "/test"     | "GET /test"
    "GET"  | "/test/"    | "GET /test"
    null   | "/test"     | "/test"
    null   | "/test/"    | "/test"
    "GET"  | null        | "/"
    "GET"  | ""          | "GET "
    "GET"  | "/"         | "GET /"
    "GET"  | "//"        | "GET /"
  }
}
