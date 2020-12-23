package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

class HttpClientDecoratorTest extends ClientDecoratorTest {

  @Shared
  def testUrl = new URI("http://myhost:123/somepath")

  def span = Mock(AgentSpan)

  def "test onRequest"() {
    setup:
    injectSysConfig(Config.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, req)

    then:
    if (req) {
      1 * span.setTag(Tags.HTTP_METHOD, req.method)
      1 * span.setTag(Tags.HTTP_URL, "$req.url")
      1 * span.setTag(Tags.PEER_HOSTNAME, req.url.host)
      1 * span.setTag(Tags.PEER_PORT, req.url.port)
      if (renameService) {
        1 * span.setServiceName(req.url.host)
      }
    }
    0 * _

    where:
    renameService | req
    false         | null
    true          | null
    false         | [method: "test-method", url: testUrl]
    true          | [method: "test-method", url: testUrl]
  }

  def "test url handling for #url"() {
    setup:
    injectSysConfig(Config.HTTP_CLIENT_TAG_QUERY_STRING, "$tagQueryString")
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, req)

    then:
    if (expectedUrl) {
      1 * span.setTag(Tags.HTTP_URL, expectedUrl)
    }
    if (expectedUrl && tagQueryString) {
      1 * span.setTag(DDTags.HTTP_QUERY, expectedQuery)
      1 * span.setTag(DDTags.HTTP_FRAGMENT, expectedFragment)
    }
    1 * span.setTag(Tags.HTTP_METHOD, null)
    if (hostname) {
      1 * span.setTag(Tags.PEER_HOSTNAME, hostname)
    }
    if (port) {
      1 * span.setTag(Tags.PEER_PORT, port)
    }
    0 * _

    where:
    tagQueryString | url                                                   | expectedUrl           | expectedQuery      | expectedFragment      | hostname | port
    false          | null                                                  | null                  | null               | null                  | null     | null
    false          | ""                                                    | "/"                   | ""                 | null                  | null     | null
    false          | "/path?query"                                         | "/path"               | ""                 | null                  | null     | null
    false          | "https://host:0"                                      | "https://host/"       | ""                 | null                  | "host"   | null
    false          | "https://host/path"                                   | "https://host/path"   | ""                 | null                  | "host"   | null
    false          | "http://host:99/path?query#fragment"                  | "http://host:99/path" | ""                 | null                  | "host"   | 99
    true           | null                                                  | null                  | null               | null                  | null     | null
    true           | ""                                                    | "/"                   | null               | null                  | null     | null
    true           | "/path?encoded+%28query%29%3F"                        | "/path"               | "encoded+(query)?" | null                  | null     | null
    true           | "https://host:0"                                      | "https://host/"       | null               | null                  | "host"   | null
    true           | "https://host/path"                                   | "https://host/path"   | null               | null                  | "host"   | null
    true           | "http://host:99/path?query#encoded+%28fragment%29%3F" | "http://host:99/path" | "query"            | "encoded+(fragment)?" | "host"   | 99

    req = [url: url == null ? null : new URI(url)]
  }

  def "test onResponse"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.onResponse(span, resp)

    then:
    if (status) {
      1 * span.setTag(Tags.HTTP_STATUS, status)
    }
    if (error) {
      1 * span.setError(true)
    }
    0 * _

    where:
    status | resp           | error
    200    | [status: 200]  | false
    399    | [status: 399]  | false
    400    | [status: 400]  | true
    499    | [status: 499]  | true
    500    | [status: 500]  | false
    500    | [status: 500]  | false
    500    | [status: 500]  | false
    600    | [status: 600]  | false
    null   | [status: null] | false
    null   | null           | false
  }

  @Override
  def newDecorator(String serviceName = "test-service") {
    return new HttpClientDecorator<Map, Map>() {
      @Override
      protected String[] instrumentationNames() {
        return ["test1", "test2"]
      }

      @Override
      protected String service() {
        return serviceName
      }

      @Override
      protected CharSequence component() {
        return "test-component"
      }

      @Override
      protected String method(Map m) {
        return m.method
      }

      @Override
      protected URI url(Map m) {
        return m.url
      }

      @Override
      protected int status(Map m) {
        null == m.status ? 0 : m.status.intValue()
      }

      protected boolean traceAnalyticsDefault() {
        return true
      }
    }
  }
}
