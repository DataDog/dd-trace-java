package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter
import datadog.trace.core.datastreams.DataStreamsMonitoring

import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING
import static datadog.trace.api.gateway.Events.EVENTS

class HttpServerDecoratorTest extends ServerDecoratorTest {

  def span = Mock(AgentSpan)

  boolean origAppSecActive

  void setup() {
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE
    ActiveSubsystems.APPSEC_ACTIVE = true
    errorPriority = ErrorPriorities.HTTP_SERVER_DECORATOR
  }

  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive
  }

  def "test onRequest"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.onRequest(this.span, null, req, null)

    then:
    if (req) {
      1 * this.span.setTag(Tags.HTTP_METHOD, "test-method")
      1 * this.span.setTag(DDTags.HTTP_QUERY, _)
      1 * this.span.setTag(DDTags.HTTP_FRAGMENT, _)
      1 * this.span.setTag(Tags.HTTP_URL, {it.toString() == url})
      1 * this.span.setTag(Tags.HTTP_HOSTNAME, req.url.host)
      2 * this.span.getRequestContext()
      1 * this.span.setResourceName({ it as String == req.method.toUpperCase() + " " + req.path }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
    } else {
      1 * this.span.getRequestContext()
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
    decorator.onRequest(this.span, null, req, null)

    then:
    if (expectedUrl) {
      1 * this.span.setTag(Tags.HTTP_URL, {it.toString() == expectedUrl})
      2 * this.span.getRequestContext()
    }
    if (expectedUrl && tagQueryString) {
      1 * this.span.setTag(DDTags.HTTP_QUERY, expectedQuery)
      1 * this.span.setTag(DDTags.HTTP_FRAGMENT, expectedFragment)
    }
    if (url != null) {
      1 * this.span.setResourceName({ it as String == expectedPath }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
      if (req.url.host != null) {
        1 * this.span.setTag(Tags.HTTP_HOSTNAME, req.url.host)
      }
    } else {
      1 * this.span.getRequestContext()
      1 * this.span.setResourceName({ it as String == expectedPath })
    }
    1 * this.span.setTag(Tags.HTTP_METHOD, null)
    0 * _

    where:
    tagQueryString | url                                                    | expectedUrl           | expectedQuery             | expectedFragment       | expectedPath
    false          | null                                                   | null                  | null                      | null                   | "/"
    false          | ""                                                     | "/"                   | ""                        | null                   | "/"
    false          | "/path?query"                                          | "/path"               | ""                        | null                   | "/path"
    false          | "https://host:0"                                       | "https://host/"       | ""                        | null                   | "/"
    false          | "https://host/path"                                    | "https://host/path"   | ""                        | null                   | "/path"
    false          | "http://host:99/path?query#fragment"                   | "http://host:99/path" | ""                        | null                   | "/path"
    true           | null                                                   | null                  | null                      | null                   | "/"
    true           | ""                                                     | "/"                   | null                      | null                   | "/"
    true           | "/path?encoded+%28query%29%3F?"                        | "/path"               | "encoded+%28query%29%3F?" | null                   | "/path"
    true           | "https://host:0"                                       | "https://host/"       | null                      | null                   | "/"
    true           | "https://host/path"                                    | "https://host/path"   | null                      | null                   | "/path"
    true           | "http://host:99/path?query#enc+%28fragment%29%3F"      | "http://host:99/path" | "query"                   | "enc+(fragment)?"      | "/path"
    true           | "http://host:99/path?query#enc+%28fragment%29%3F?tail" | "http://host:99/path" | "query"                   | "enc+(fragment)??tail" | "/path"

    req = [url: url == null ? null : new URI(url)]
  }

  def "test url handling for #url rawQuery=#rawQuery rawResource=#rawResource"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    injectSysConfig(HTTP_SERVER_RAW_QUERY_STRING, "$rawQuery")
    injectSysConfig(HTTP_SERVER_RAW_RESOURCE, "$rawResource")
    def decorator = newDecorator()

    when:
    decorator.onRequest(this.span, null, req, null)

    then:
    1 * this.span.setTag(Tags.HTTP_URL, {it.toString() == expectedUrl})
    1 * this.span.setTag(Tags.HTTP_HOSTNAME, req.url.host)
    1 * this.span.setTag(DDTags.HTTP_QUERY, expectedQuery)
    1 * this.span.setTag(DDTags.HTTP_FRAGMENT, null)
    2 * this.span.getRequestContext()
    1 * this.span.setResourceName({ it as String == expectedResource }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
    1 * this.span.setTag(Tags.HTTP_METHOD, null)
    0 * _

    where:
    rawQuery | rawResource | url                             | expectedUrl           | expectedQuery | expectedResource
    false    | false       | "http://host/p%20ath?query%3F?" | "http://host/p ath"   | "query??"     | "/path"
    false    | true        | "http://host/p%20ath?query%3F?" | "http://host/p%20ath" | "query??"     | "/p%20ath"
    true     | false       | "http://host/p%20ath?query%3F?" | "http://host/p ath"   | "query%3F?"   | "/path"
    true     | true        | "http://host/p%20ath?query%3F?" | "http://host/p%20ath" | "query%3F?"   | "/p%20ath"

    req = [url: url == null ? null : new URI(url)]
  }

  def "test onConnection"() {
    setup:
    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    decorator.onRequest(this.span, conn, null, ctx)

    then:
    _ * ctx.getForwarded() >> "by=<identifier>;for=<identifier>;host=<host>;proto=<http|https>"
    _ * ctx.getForwardedFor() >> null
    _ * ctx.getXForwardedProto() >> "https"
    _ * ctx.getXForwardedHost() >> "somehost"
    _ * ctx.getXForwardedFor() >> conn?.ip
    _ * ctx.getXForwardedPort() >> "123"
    _ * ctx.getXForwarded()
    _ * ctx.getXClusterClientIp() >> null
    _ * ctx.getXRealIp() >> null
    _ * ctx.getXClientIp() >> null
    _ * ctx.getUserAgent() >> "some-user-agent"
    _ * ctx.getCustomIpHeader() >> null
    _ * ctx.getTrueClientIp() >> null
    _ * ctx.getFastlyClientIp() >> null
    _ * ctx.getCfConnectingIp() >> null
    _ * ctx.getCfConnectingIpv6() >> null
    1 * this.span.setTag(Tags.HTTP_FORWARDED, "by=<identifier>;for=<identifier>;host=<host>;proto=<http|https>")
    1 * this.span.setTag(Tags.HTTP_FORWARDED_PROTO, "https")
    1 * this.span.setTag(Tags.HTTP_FORWARDED_HOST, "somehost")
    if (conn?.peerIp) {
      1 * this.span.setTag(ipv4 ? Tags.PEER_HOST_IPV4 : Tags.PEER_HOST_IPV6, conn.peerIp)
    }
    if (conn?.ip) {
      1 * this.span.setTag(Tags.HTTP_CLIENT_IP, conn.ip)
      1 * this.span.setTag(Tags.HTTP_FORWARDED_IP, conn?.ip)
    } else if (conn?.peerIp) {
      1 * this.span.setTag(Tags.HTTP_CLIENT_IP, conn.peerIp)
    }
    1 * this.span.setTag(Tags.HTTP_FORWARDED_PORT, "123")
    if (conn?.port) {
      1 * this.span.setTag(Tags.PEER_PORT, conn.port)
    }
    1 * this.span.setTag(Tags.HTTP_USER_AGENT, "some-user-agent")
    _ * this.span.getRequestContext() >> null
    0 * _

    where:
    ipv4  | conn
    null  | null
    true  | [ip: null, peerIp: '127.0.0.1', port: 555]
    true  | [ip: '10.0.0.1', port: 555]
    false | [ip: "3ffe:1900:4545:3:200:f8ff:fe21:67cf", port: 555]
  }

  void 'preference for header derived vs peer ip address'() {
    setup:
    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    1 * ctx.getXClientIp() >> headerIpAddr
    decorator.onRequest(this.span, [peerIp: peerIpAddr], null, ctx)

    then:
    1 * this.span.setTag(Tags.HTTP_CLIENT_IP, result)

    where:
    headerIpAddr | peerIpAddr  | result
    '1.1.1.1'    | '8.8.8.8'   | '1.1.1.1'
    '1.1.1.1'    | '127.0.0.1' | '1.1.1.1'
    '127.0.0.1'  | '8.8.8.8'   | '8.8.8.8'
    null         | '127.0.0.1' | '127.0.0.1'
  }

  void 'disabling ip resolution disables header collection and ip address resolution'() {
    setup:
    injectSysConfig('dd.trace.client-ip.resolver.enabled', 'false')

    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    decorator.onRequest(this.span, [peerIp: '4.4.4.4'], null, ctx)

    then:
    0 * ctx.getForwarded()
    0 * span.setTag(Tags.HTTP_FORWARDED, _)
    0 * this.span.setTag(Tags.HTTP_CLIENT_IP, _)
  }

  void 'disabling appsec disables header collection and ip address resolution'() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = false

    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    decorator.onRequest(this.span, [peerIp: '4.4.4.4'], null, ctx)

    then:
    0 * ctx.getForwarded()
    0 * span.setTag(Tags.HTTP_FORWARDED, _)
    0 * this.span.setTag(Tags.HTTP_CLIENT_IP, _)
  }

  void 'disabling appsec but enabling client_ip_without_appsec enables header collection and ip address resolution'() {
    setup:
    injectSysConfig('dd.trace.client-ip.enabled', 'true')
    ActiveSubsystems.APPSEC_ACTIVE = false

    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    decorator.onRequest(this.span, [peerIp: '4.4.4.4'], null, ctx)

    then:
    2 * ctx.getXForwardedFor() >> '2.3.4.5'
    1 * this.span.setTag(Tags.HTTP_CLIENT_IP, '2.3.4.5')
    1 * this.span.setTag(Tags.HTTP_FORWARDED_IP, '2.3.4.5')

    // Forwarded doesn't participate in client ip resolution anymore
    1 * ctx.getForwarded() >> 'for=9.9.9.9'
    1 * this.span.setTag(Tags.HTTP_FORWARDED, 'for=9.9.9.9')
  }

  void 'client ip reporting with custom header'() {
    setup:
    injectSysConfig('dd.trace.client-ip-header', 'my-header')

    def ctx = Mock(AgentSpan.Context.Extracted)
    def decorator = newDecorator()

    when:
    decorator.onRequest(this.span, [peerIp: '4.4.4.4'], null, ctx)

    then:
    1 * ctx.getCustomIpHeader() >> '5.5.5.5'
    1 * this.span.setTag(Tags.HTTP_CLIENT_IP, '5.5.5.5')
  }

  def "test onResponse"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.onResponse(this.span, resp)

    then:
    if (status) {
      1 * this.span.setHttpStatusCode(status)
      1 * this.span.setError(error, ErrorPriorities.HTTP_SERVER_DECORATOR)
    }
    if (status == 404) {
      1 * this.span.setResourceName({ it as String == "404" }, ResourceNamePriorities.HTTP_404)
    }
    if (resp) {
      1 * this.span.getRequestContext()
    }
    _ * span.getTag('error.type')
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
          return m.peerIp
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
    def ss = ig.getSubscriptionService(RequestContextSlot.APPSEC)
    def cbpAppSec = ig.getCallbackProvider(RequestContextSlot.APPSEC)
    def callbacks = new IGCallBacks(reqData)
    if (reqStarted) {
      ss.registerCallback(EVENTS.requestStarted(), callbacks)
    }
    if (reqHeader) {
      ss.registerCallback(EVENTS.requestHeader(), callbacks)
    }
    if (reqHeaderDone) {
      ss.registerCallback(EVENTS.requestHeaderDone(), callbacks)
    }
    Map<String, String> headers = ["foo": "bar", "some": "thing", "another": "value"]
    def reqCtxt = Mock(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> reqData
    }
    def mSpan = Mock(AgentSpan) {
      getRequestContext() >> reqCtxt
    }

    def mTracer = Mock(TracerAPI) {
      startSpan(_, _, _) >> mSpan
      getCallbackProvider(RequestContextSlot.APPSEC) >> cbpAppSec
      getCallbackProvider(RequestContextSlot.IAST) >> CallbackProvider.CallbackProviderNoop.INSTANCE
      getDataStreamsMonitoring() >> Mock(DataStreamsMonitoring)
    }
    def decorator = newDecorator(mTracer)

    when:
    decorator.startSpan("test", headers, null)

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
  TriConsumer<RequestContext, String, String>,
  Function<RequestContext, Flow<Void>> {

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
    void accept(RequestContext requestContext, String key, String value) {
      assert (requestContext.getData(RequestContextSlot.APPSEC) == this.data)
      headers.put(key, value)
    }

    // REQUEST_HEADER_DONE
    @Override
    Flow<Void> apply(RequestContext requestContext) {
      assert (requestContext.getData(RequestContextSlot.APPSEC) == this.data)
      reqHeaderDoneCount++
      Flow.ResultFlow.empty()
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
