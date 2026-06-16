package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.api.appsec.HttpClientRequest
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.gateway.CallbackProvider
import static datadog.trace.api.gateway.Events.EVENTS
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING

import java.util.function.BiFunction

class HttpClientDecoratorTest extends ClientDecoratorTest {

  @Shared
  def testUrl = new URI("http://myhost:123/somepath")

  @Shared
  protected static final ORIGINAL_TRACER = AgentTracer.get()

  protected traceSegment
  protected reqCtx
  protected span2
  protected tracer

  void setup() {
    traceSegment = Stub(TraceSegment)
    reqCtx = Stub(RequestContext) {
      getTraceSegment() >> traceSegment
    }
    span2 = Stub(AgentSpan) {
      getRequestContext() >> reqCtx
    }
    tracer = Stub(AgentTracer.TracerAPI) {
      activeSpan() >> span2
    }
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  def span = Mock(AgentSpan)

  def "test onRequest"() {
    setup:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, req)

    then:
    if (req) {
      1 * span.setTag(Tags.HTTP_METHOD, req.method)
      1 * span.setTag(Tags.HTTP_URL, {it.toString() == "$req.url"})
      1 * span.setTag(DDTags.HTTP_QUERY, null)
      1 * span.setTag(DDTags.HTTP_FRAGMENT, null)
      1 * span.setTag(Tags.PEER_HOSTNAME, req.url.host)
      1 * span.setTag(Tags.PEER_PORT, req.url.port)
      1 * span.setResourceName({ it as String == req.method.toUpperCase() + " " + req.path }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
      if (renameService) {
        1 * span.setServiceName(req.url.host, _)
      }
      1 * span.traceConfig() >> AgentTracer.traceConfig()
    }
    0 * _

    where:
    renameService | req
    false         | null
    true          | null
    false         | [method: "test-method", url: testUrl, path: '/somepath']
    true          | [method: "test-method", url: testUrl, path: '/somepath']
  }

  def "test url handling for #url"() {
    setup:
    injectSysConfig(HTTP_CLIENT_TAG_QUERY_STRING, "$tagQueryString")
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, req)

    then:
    if (expectedUrl) {
      1 * span.setTag(Tags.HTTP_URL, {it.toString() == expectedUrl})
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
    if (url != null) {
      1 * span.setResourceName({ it as String == expectedPath }, ResourceNamePriorities.HTTP_PATH_NORMALIZER)
    } else {
      1 * span.setResourceName({ it as String == expectedPath })
    }
    if (req) {
      1 * span.traceConfig() >> AgentTracer.traceConfig()
    }
    0 * _

    where:
    tagQueryString | url                                                   | expectedUrl           | expectedQuery      | expectedFragment      | hostname | port | expectedPath
    false          | null                                                  | null                  | null               | null                  | null     | null | "/"
    false          | ""                                                    | "/"                   | ""                 | null                  | null     | null | "/"
    false          | "/path?query"                                         | "/path"               | ""                 | null                  | null     | null | "/path"
    false          | "https://host:0"                                      | "https://host/"       | ""                 | null                  | "host"   | null | "/"
    false          | "https://host/path"                                   | "https://host/path"   | ""                 | null                  | "host"   | null | "/path"
    false          | "http://host:99/path?query#fragment"                  | "http://host:99/path" | ""                 | null                  | "host"   | 99   | "/path"
    true           | null                                                  | null                  | null               | null                  | null     | null | "/"
    true           | ""                                                    | "/"                   | null               | null                  | null     | null | "/"
    true           | "/path?encoded+%28query%29%3F"                        | "/path"               | "encoded+(query)?" | null                  | null     | null | "/path"
    true           | "https://host:0"                                      | "https://host/"       | null               | null                  | "host"   | null | "/"
    true           | "https://host/path"                                   | "https://host/path"   | null               | null                  | "host"   | null | "/path"
    true           | "http://host:99/path?query#encoded+%28fragment%29%3F" | "http://host:99/path" | "query"            | "encoded+(fragment)?" | "host"   | 99   | "/path"

    req = [url: url == null ? null : new URI(url)]
  }

  def "test split-by-domain #url"() {
    setup:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    def decorator = newDecorator()

    when:
    decorator.onRequest(span, req)

    then:
    if (expectedServiceName) {
      1 * span.setServiceName(expectedServiceName, _)
    }
    if (url != null) {
      1 * span.setResourceName(_, _)
    } else {
      1 * span.setResourceName(_)
    }
    if (req) {
      1 * span.traceConfig() >> AgentTracer.traceConfig()
    }
    _ * span.setTag(_, _)
    0 * _

    where:
    url                                   | expectedServiceName
    null                                  | null
    ""                                    | null
    "/path?query"                         | null
    "http://host:0"                       | "host"
    "http://ahost:0"                      | "ahost"
    "http://AHOST:0"                      | "AHOST"
    "https://host123/path"                | "host123"
    "https://123host/path"                | null
    "http://10.20.30.40"                  | null

    req = [url: url == null ? null : new URI(url)]
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
    if (resp) {
      1 * span.traceConfig() >> AgentTracer.traceConfig()
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

  def "test ssrfIastCheck is called"() {
    setup:
    injectSysConfig('dd.iast.enabled', input)
    def decorator = newDecorator()
    final module = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    decorator.onRequest(span, req)

    then:
    if (input == 'true') {
      1 * module.onURLConnection(_)
    } else {
      0 * module.onURLConnection(_)
    }
    if (req) {
      1 * span.traceConfig() >> AgentTracer.traceConfig()
    }

    where:
    input  | req
    'true' | [method: "test-method", url: testUrl, path: '/somepath']
    'false' | [method: "test-method", url: testUrl, path: '/somepath']
  }

  void "test SSRF Exploit prevention onResponse"(){
    setup:
    injectSysConfig(AppSecConfig.APPSEC_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, 'true')

    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    final decorator = newDecorator()
    final req = [method: "GET", url: new URI("http://localhost:1234/somepath")]

    when:
    decorator.onRequest(span2,  req)

    then:
    1 * callbackProvider.getCallback(EVENTS.httpClientRequest()) >> listener
    1 * listener.apply(reqCtx, _ as HttpClientRequest)
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
        protected String sourceUrl(Map m) {
          return m.url
        }

        @Override
        protected int status(Map m) {
          null == m.status ? 0 : m.status.intValue()
        }

        @Override
        protected boolean traceAnalyticsDefault() {
          return true
        }

        @Override
        protected String getRequestHeader(Map map, String headerName) {
          return null
        }

        @Override
        protected String getResponseHeader(Map map, String headerName) {
          return null
        }
      }
  }
}
