package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.api.function.Function
import datadog.trace.api.function.Supplier
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter

import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING
import static datadog.trace.api.gateway.Events.EVENTS

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
      1 * span.setTag(DDTags.HTTP_QUERY, _)
      1 * span.setTag(DDTags.HTTP_FRAGMENT, _)
      1 * span.setTag(Tags.HTTP_URL, url)
      1 * span.setTag(Tags.HTTP_HOSTNAME, req.url.host)
      1 * span.getRequestContext()
      1 * span.setResourceName({ it as String == req.method.toUpperCase() + " " + req.path }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
    }
    0 * _

    where:
    req                                                                                       | url
    null                                                                                      | _
    [method: "test-method", url: URI.create("http://test-url?some=query"), path: '/']         | "http://test-url/?some=query"
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
      1 * span.getRequestContext()
    }
    if (expectedUrl && tagQueryString) {
      1 * span.setTag(DDTags.HTTP_QUERY, expectedQuery)
      1 * span.setTag(DDTags.HTTP_FRAGMENT, expectedFragment)
    }
    if (url != null) {
      1 * span.setResourceName({ it as String == expectedPath }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
      if (req.url.host != null) {
        1 * span.setTag(Tags.HTTP_HOSTNAME, req.url.host)
      }
    } else {
      1 * span.setResourceName({ it as String == expectedPath })
    }
    1 * span.setTag(Tags.HTTP_METHOD, null)
    0 * _

    where:
    tagQueryString | url                                                    | expectedUrl                  | expectedQuery        | expectedFragment       | expectedPath
    false          | null                                                   | null                         | null                 | null                   | "/"
    false          | ""                                                     | "/"                          | ""                   | null                   | "/"
    false          | "/path?query"                                          | "/path"                      | ""                   | null                   | "/path"
    false          | "https://host:0"                                       | "https://host/"              | ""                   | null                   | "/"
    false          | "https://host/path"                                    | "https://host/path"          | ""                   | null                   | "/path"
    false          | "http://host:99/path?query#fragment"                   | "http://host:99/path"        | ""                   | null                   | "/path"
    true           | null                                                   | null                         | null                 | null                   | "/"
    true           | ""                                                     | "/"                          | null                 | null                   | "/"
    true           | "/path?encoded+%28query%29%3F?"                        | "/path?encoded+%28query%29%3F?"    | "encoded+%28query%29%3F?"  | null                   | "/path"
    true           | "https://host:0"                                       | "https://host/"              | null                 | null                   | "/"
    true           | "https://host/path"                                    | "https://host/path"          | null                 | null                   | "/path"
    true           | "http://host:99/path?query#enc+%28fragment%29%3F"      | "http://host:99/path?query"  | "query"              | "enc+(fragment)?"      | "/path"
    true           | "http://host:99/path?query#enc+%28fragment%29%3F?tail" | "http://host:99/path?query"  | "query"              | "enc+(fragment)??tail" | "/path"

    req = [url: url == null ? null : new URI(url)]
  }

  def "test url handling for #url rawQuery=#rawQuery rawResource=#rawResource"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    injectSysConfig(HTTP_SERVER_RAW_QUERY_STRING, "$rawQuery")
    injectSysConfig(HTTP_SERVER_RAW_RESOURCE, "$rawResource")
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, null, req, null)

    then:
    1 * span.setTag(Tags.HTTP_URL, expectedUrl)
    1 * span.setTag(Tags.HTTP_HOSTNAME, req.url.host)
    1 * span.setTag(DDTags.HTTP_QUERY, expectedQuery)
    1 * span.setTag(DDTags.HTTP_FRAGMENT, null)
    1 * span.getRequestContext()
    1 * span.setResourceName({ it as String == expectedResource }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
    1 * span.setTag(Tags.HTTP_METHOD, null)
    0 * _

    where:
    rawQuery | rawResource | url                             | expectedUrl                     | expectedQuery  | expectedResource
    false    | false       | "http://host/p%20ath?query%3F?" | "http://host/p ath?query??"     | "query??"      | "/path"
    false    | true        | "http://host/p%20ath?query%3F?" | "http://host/p%20ath?query??"   | "query??"      | "/p%20ath"
    true     | false       | "http://host/p%20ath?query%3F?" | "http://host/p ath?query%3F?"   | "query%3F?"    | "/path"
    true     | true        | "http://host/p%20ath?query%3F?" | "http://host/p%20ath?query%3F?" | "query%3F?"    | "/p%20ath"

    req = [url: url == null ? null : new URI(url)]
  }

  def "test onConnection"() {
    setup:
    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, conn, null, ctx)

    then:
    _ * ctx.getForwarded() >> null
    _ * ctx.getForwardedFor() >> null
    _ * ctx.getForwardedProto() >> null
    _ * ctx.getForwardedHost() >> null
    _ * ctx.getForwardedIp() >> null
    _ * ctx.getForwardedPort() >> null
    _ * ctx.getXForwarded()
    _ * ctx.getXForwardedFor() >> null
    _ * ctx.getXClusterClientIp() >> null
    _ * ctx.getXRealIp() >> null
    _ * ctx.getClientIp() >> null
    _ * ctx.getUserAgent() >> null
    _ * ctx.getCustomIpHeader() >> null
    _ * ctx.getTrueClientIp() >> null
    _ * ctx.getVia() >> null
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
    _ * ctx.getForwarded() >> "by=<identifier>;for=<identifier>;host=<host>;proto=<http|https>"
    _ * ctx.getForwardedFor() >> null
    _ * ctx.getForwardedProto() >> "https"
    _ * ctx.getForwardedHost() >> "somehost"
    _ * ctx.getForwardedIp() >> (ipv4 ? "10.1.1.1, 192.168.1.1" : "0::1")
    _ * ctx.getForwardedPort() >> "123"
    _ * ctx.getXForwarded()
    _ * ctx.getXForwardedFor() >> null
    _ * ctx.getXClusterClientIp() >> null
    _ * ctx.getXRealIp() >> null
    _ * ctx.getClientIp() >> null
    _ * ctx.getUserAgent() >> "some-user-agent"
    _ * ctx.getCustomIpHeader() >> null
    _ * ctx.getTrueClientIp() >> null
    _ * ctx.getVia() >> null
    1 * span.setTag(Tags.HTTP_FORWARDED, "by=<identifier>;for=<identifier>;host=<host>;proto=<http|https>")
    1 * span.setTag(Tags.HTTP_FORWARDED_PROTO, "https")
    1 * span.setTag(Tags.HTTP_FORWARDED_HOST, "somehost")
    if (ipv4) {
      1 * span.setTag(Tags.HTTP_FORWARDED_IP, "10.1.1.1, 192.168.1.1")
      1 * span.setTag(Tags.PEER_HOST_IPV4, "10.0.0.1")
    } else if (conn?.ip) {
      1 * span.setTag(Tags.HTTP_FORWARDED_IP, "0::1")
      1 * span.setTag(Tags.PEER_HOST_IPV6, "3ffe:1900:4545:3:200:f8ff:fe21:67cf")
    } else {
      1 * span.setTag(Tags.HTTP_FORWARDED_IP, "0::1")
    }
    1 * span.setTag(Tags.HTTP_FORWARDED_PORT, "123")
    if (conn) {
      1 * span.setTag(Tags.PEER_PORT, 555)
    }
    1 * span.setTag(Tags.HTTP_USER_AGENT, "some-user-agent")
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
      1 * span.setHttpStatusCode(status)
    }
    if (error) {
      1 * span.setError(true)
    }
    if (status == 404) {
      1 * span.setResourceName({ it as String == "404" }, ResourceNamePriorities.HTTP_404)
    }
    if (resp) {
      1 * span.getRequestContext()
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
    return newDecorator(null)
  }

  def newDecorator(TracerAPI tracer) {
    if (!tracer) {
      tracer = AgentTracer.NOOP_TRACER
    }

    return new HttpServerDecorator<Map, Map, Map, Map<String, String>>() {
        @Override
        protected TracerAPI tracer() {
          return tracer
        }

        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
        }

        @Override
        protected CharSequence component() {
          return "test-component"
        }

        @Override
        protected AgentPropagation.ContextVisitor<Map<String, String>> getter() {
          return ContextVisitors.stringValuesMap()
        }

        @Override
        protected AgentPropagation.ContextVisitor<Map> responseGetter() {
          return null
        }

        @Override
        CharSequence spanName() {
          return "http-test-span"
        }

        @Override
        protected String method(Map m) {
          return m.method
        }

        @Override
        protected URIDataAdapter url(Map m) {
          return m.url == null ? null : new URIDefaultDataAdapter(m.url)
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

  def "test startSpan and InstrumentationGateway"() {
    setup:
    def ig = new InstrumentationGateway()
    def callbacks = new IGCallBacks(reqData)
    if (reqStarted) {
      ig.registerCallback(EVENTS.requestStarted(), callbacks)
    }
    if (reqHeader) {
      ig.registerCallback(EVENTS.requestHeader(), callbacks)
    }
    if (reqHeaderDone) {
      ig.registerCallback(EVENTS.requestHeaderDone(), callbacks)
    }
    Map<String, String> headers = ["foo": "bar", "some": "thing", "another": "value"]
    def reqCtxt = Mock(RequestContext) {
      getData() >> reqData
    }
    def mSpan = Mock(AgentSpan) {
      getRequestContext() >> reqCtxt
    }
    def mTracer = Mock(TracerAPI) {
      startSpan(_, _, _) >> mSpan
      instrumentationGateway() >> ig
    }
    def decorator = newDecorator(mTracer)

    when:
    decorator.startSpan(headers, null)

    then:
    1 * mSpan.setMeasured(true) >> mSpan
    reqStartedCount == callbacks.reqStartedCount
    reqHeaderCount == callbacks.headers.size()
    reqHeaderDoneCount == callbacks.reqHeaderDoneCount
    reqHeaderCount == 0 ? true : headers == callbacks.headers

    where:
    // spotless:off
    reqStarted | reqData      | reqHeader | reqHeaderDone | reqStartedCount | reqHeaderCount | reqHeaderDoneCount
    false      | null         | false     | false         | 0               | 0              | 0
    false      | new Object() | false     | false         | 0               | 0              | 0
    true       | null         | false     | false         | 1               | 0              | 0
    true       | new Object() | false     | false         | 1               | 0              | 0
    true       | new Object() | true      | false         | 1               | 3              | 0
    true       | new Object() | true      | true          | 1               | 3              | 1
    // spotless:on
  }

  private static final class IGCallBacks implements
  Supplier<Flow<Object>>,
  TriConsumer<RequestContext<Object>, String, String>,
  Function<RequestContext<Object>, Flow<Void>> {

    private final Object data
    private final Map<String, String> headers = new HashMap<>()
    private int reqStartedCount = 0
    private int reqHeaderDoneCount = 0

    IGCallBacks(Object data) {
      this.data = data
    }

    // REQUEST_STARTED
    @Override
    Flow<Object> get() {
      reqStartedCount++
      return null == data ? Flow.ResultFlow.empty() : new Flow.ResultFlow(data)
    }

    // REQUEST_HEADER
    @Override
    void accept(RequestContext<Object> requestContext, String key, String value) {
      assert (requestContext.data == this.data)
      headers.put(key, value)
    }

    // REQUEST_HEADER_DONE
    @Override
    Flow<Void> apply(RequestContext<Object> requestContext) {
      assert (requestContext.data == this.data)
      reqHeaderDoneCount++
      return null
    }

    int getReqStartedCount() {
      return reqStartedCount
    }

    Map<String, String> getHeaders() {
      return headers
    }

    int getReqHeaderDoneCount() {
      return reqHeaderDoneCount
    }
  }
}
