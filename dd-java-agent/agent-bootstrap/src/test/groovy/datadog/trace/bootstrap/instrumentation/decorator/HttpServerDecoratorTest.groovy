package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.DefaultURIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter

import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING

class HttpServerDecoratorTest extends ServerDecoratorTest {

  def span = Mock(AgentSpan)

  def "test onRequest"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, null, req, null)

    then:
    if (req) {
      1 * span.setTag(Tags.HTTP_METHOD, "test-method")
      1 * span.setTag(Tags.HTTP_URL, url)
      1 * span.hasResourceName() >> false
      1 * span.setResourceName({ it as String == req.method + " " + req.path })
    }
    0 * _

    where:
    req                                                                                       | url
    null                                                                                      | _
    [method: "test-method", url: URI.create("http://test-url?some=query"), path: '/']         | "http://test-url/"
    [method: "test-method", url: URI.create("http://a:80/"), path: '/']                       | "http://a/"
    [method: "test-method", url: URI.create("https://10.0.0.1:443"), path: '/']               | "https://10.0.0.1/"
    [method: "test-method", url: URI.create("https://localhost:0/1/"), path: '/?/']           | "https://localhost/1/"
    [method: "test-method", url: URI.create("http://123:8080/some/path"), path: '/some/path'] | "http://123:8080/some/path"
  }

  def "test url handling for #url"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "$tagQueryString")
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, null, req, null)

    then:
    if (expectedUrl) {
      1 * span.setTag(Tags.HTTP_URL, expectedUrl)
    }
    if (expectedUrl && tagQueryString) {
      1 * span.setTag(DDTags.HTTP_QUERY, expectedQuery)
      1 * span.setTag(DDTags.HTTP_FRAGMENT, expectedFragment)
    }
    1 * span.hasResourceName() >> false
    1 * span.setResourceName({ it as String == expectedPath })
    1 * span.setTag(Tags.HTTP_METHOD, null)
    0 * _

    where:
    tagQueryString | url                                                    | expectedUrl           | expectedQuery       | expectedFragment       | expectedPath
    false          | null                                                   | null                  | null                | null                   | "/"
    false          | ""                                                     | "/"                   | ""                  | null                   | "/"
    false          | "/path?query"                                          | "/path"               | ""                  | null                   | "/path"
    false          | "https://host:0"                                       | "https://host/"       | ""                  | null                   | "/"
    false          | "https://host/path"                                    | "https://host/path"   | ""                  | null                   | "/path"
    false          | "http://host:99/path?query#fragment"                   | "http://host:99/path" | ""                  | null                   | "/path"
    true           | null                                                   | null                  | null                | null                   | "/"
    true           | ""                                                     | "/"                   | null                | null                   | "/"
    true           | "/path?encoded+%28query%29%3F?"                        | "/path"               | "encoded+(query)??" | null                   | "/path"
    true           | "https://host:0"                                       | "https://host/"       | null                | null                   | "/"
    true           | "https://host/path"                                    | "https://host/path"   | null                | null                   | "/path"
    true           | "http://host:99/path?query#enc+%28fragment%29%3F"      | "http://host:99/path" | "query"             | "enc+(fragment)?"      | "/path"
    true           | "http://host:99/path?query#enc+%28fragment%29%3F?tail" | "http://host:99/path" | "query"             | "enc+(fragment)??tail" | "/path"

    req = [url: url == null ? null : new URI(url)]
  }

  def "test onConnection"() {
    setup:
    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, conn, null, ctx)

    then:
    1 * ctx.getForwardedFor() >> null
    1 * ctx.getForwardedPort() >> null
    if (conn) {
      1 * span.setTag(Tags.PEER_PORT, 555)
      if (ipv4) {
        1 * span.setTag(Tags.PEER_HOST_IPV4, "10.0.0.1")
      } else if (ipv4 != null) {
        1 * span.setTag(Tags.PEER_HOST_IPV6, "3ffe:1900:4545:3:200:f8ff:fe21:67cf")
      }
    }
    0 * _

    when:
    decorator.onRequest(span, conn, null, ctx)

    then:
    1 * ctx.getForwardedFor() >> (ipv4 ? "10.1.1.1" : "0::1")
    1 * ctx.getForwardedPort() >> "123"
    if (ipv4) {
      1 * span.setTag(Tags.PEER_HOST_IPV4, "10.1.1.1")
    } else {
      1 * span.setTag(Tags.PEER_HOST_IPV6, "0::1")
    }
    1 * span.setTag(Tags.PEER_PORT, "123")
    0 * _

    where:
    ipv4  | conn
    null  | null
    null  | [ip: null, port: 555]
    true  | [ip: "10.0.0.1", port: 555]
    false | [ip: "3ffe:1900:4545:3:200:f8ff:fe21:67cf", port: 555]
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
    if (status == 404) {
      1 * span.hasResourceName() >> false
      1 * span.setResourceName({ it as String == "404" })
    }
    0 * _

    where:
    status | resp           | error
    200    | [status: 200]  | false
    399    | [status: 399]  | false
    400    | [status: 400]  | false
    404    | [status: 404]  | false
    404    | [status: 404]  | false
    499    | [status: 499]  | false
    500    | [status: 500]  | true
    600    | [status: 600]  | false
    null   | [status: null] | false
    null   | null           | false
  }

  @Override
  def newDecorator() {
    return new HttpServerDecorator<Map, Map, Map>() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
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
        protected URIDataAdapter url(Map m) {
          return m.url == null ? null : new DefaultURIDataAdapter(m.url)
        }

        @Override
        protected String peerHostIP(Map m) {
          return m.ip
        }

        @Override
        protected int peerPort(Map m) {
          return m.port == null ? 0 : m.port
        }

        @Override
        protected int status(Map m) {
          return m.status == null ? 0 : m.status
        }
      }
  }
}
