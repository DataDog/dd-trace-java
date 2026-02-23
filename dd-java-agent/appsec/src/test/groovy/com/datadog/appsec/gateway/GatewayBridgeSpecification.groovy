package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.api.security.ApiSecurityDownstreamSampler
import com.datadog.appsec.api.security.ApiSecuritySamplerImpl
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.report.AppSecEvent
import com.datadog.appsec.report.AppSecEventWrapper
import datadog.trace.api.ProductTraceSource
import datadog.trace.api.TagMap
import datadog.trace.api.appsec.HttpClientRequest
import datadog.trace.api.appsec.HttpClientResponse
import datadog.trace.api.appsec.MediaType
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.telemetry.LoginEvent
import datadog.trace.api.telemetry.RuleType
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.api.gateway.Events.EVENTS
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP

@SuppressWarnings('UnusedVariable')
class GatewayBridgeSpecification extends DDSpecification {

  @Shared
  protected static final ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get()

  private static final String USER_ID = 'user'

  SubscriptionService ig = Mock()
  EventDispatcher eventDispatcher = Mock()
  AppSecRequestContext arCtx = new AppSecRequestContext()
  TraceSegment traceSegment = Mock()
  RequestContext ctx = new RequestContext() {
    final AppSecRequestContext data = arCtx
    BlockResponseFunction blockResponseFunction

    @Override
    Object getData(RequestContextSlot slot) {
      slot == RequestContextSlot.APPSEC ? data : null
    }

    @Override
    final TraceSegment getTraceSegment() {
      GatewayBridgeSpecification.this.traceSegment
    }

    @Override
    def getOrCreateMetaStructTop(String key, Function defaultValue) {
      return null
    }

    @Override
    void close() throws IOException {}
  }
  EventProducerService.DataSubscriberInfo nonEmptyDsInfo = {
    EventProducerService.DataSubscriberInfo i = Stub()
    i.empty >> false
    i
  }()

  EventProducerService.DataSubscriberInfo emptyDsInfo = Stub {
    isEmpty() >> true
  }

  TraceSegmentPostProcessor pp = Mock()
  ApiSecuritySamplerImpl requestSampler = Mock(ApiSecuritySamplerImpl)
  ApiSecurityDownstreamSampler downstreamSampler = Mock(ApiSecurityDownstreamSampler)
  GatewayBridge bridge = new GatewayBridge(ig, eventDispatcher, () -> requestSampler, downstreamSampler, [pp])

  Supplier<Flow<AppSecRequestContext>> requestStartedCB
  BiFunction<RequestContext, AgentSpan, Flow<Void>> requestEndedCB
  TriConsumer<RequestContext, String, String> reqHeaderCB
  Function<RequestContext, Flow<Void>> reqHeadersDoneCB
  TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> requestMethodURICB
  BiFunction<RequestContext, Map<String, Object>, Flow<Void>> pathParamsCB
  TriFunction<RequestContext, String, Integer, Flow<Void>> requestSocketAddressCB
  BiFunction<RequestContext, String, Flow<Void>> requestInferredAddressCB
  BiFunction<RequestContext, StoredBodySupplier, Void> requestBodyStartCB
  BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestBodyDoneCB
  BiFunction<RequestContext, Object, Flow<Void>> requestBodyProcessedCB
  BiFunction<RequestContext, Object, Flow<Void>> responseBodyCB
  BiFunction<RequestContext, Integer, Flow<Void>> responseStartedCB
  TriConsumer<RequestContext, String, String> respHeaderCB
  Function<RequestContext, Flow<Void>> respHeadersDoneCB
  BiFunction<RequestContext, String, Flow<Void>> grpcServerMethodCB
  BiFunction<RequestContext, Object, Flow<Void>> grpcServerRequestMessageCB
  BiFunction<RequestContext, Map<String, Object>, Flow<Void>> graphqlServerRequestMessageCB
  BiConsumer<RequestContext, String> databaseConnectionCB
  BiFunction<RequestContext, String, Flow<Void>> databaseSqlQueryCB
  BiFunction<RequestContext, HttpClientRequest, Flow<Void>> httpClientRequestCB
  BiFunction<RequestContext, HttpClientResponse, Flow<Void>> httpClientResponseCB
  BiFunction<RequestContext, Long, Flow<Void>> httpClientSamplingCB
  BiFunction<RequestContext, String, Flow<Void>> fileLoadedCB
  BiFunction<RequestContext, String, Flow<Void>> requestSessionCB
  BiFunction<RequestContext, String[], Flow<Void>> execCmdCB
  BiFunction<RequestContext, String, Flow<Void>> shellCmdCB
  BiFunction<RequestContext, String, Flow<Void>> userCB
  TriFunction<RequestContext, LoginEvent, String, Flow<Void>> loginEventCB
  BiConsumer<RequestContext, String> httpRouteCB

  WafMetricCollector wafMetricCollector = Mock(WafMetricCollector)

  void setup() {
    callInitAndCaptureCBs()
    AppSecSystem.active = true
    WafMetricCollector.INSTANCE = wafMetricCollector
  }

  void cleanup() {
    WafMetricCollector.INSTANCE  = ORIGINAL_METRIC_COLLECTOR
    bridge.stop()
  }

  void 'request_start produces appsec context and publishes event'() {
    when:
    Flow<AppSecRequestContext> startFlow = requestStartedCB.get()

    then:
    Object producedCtx = startFlow.getResult()
    producedCtx instanceof AppSecRequestContext
    startFlow.action == Flow.Action.Noop.INSTANCE
  }

  void 'request_start returns null context if appsec is disabled'() {
    setup:
    AppSecSystem.active = false

    when:
    Flow<AppSecRequestContext> startFlow = requestStartedCB.get()

    then:
    Object producedCtx = startFlow.getResult()
    producedCtx == null
    0 * _._

    cleanup:
    AppSecSystem.active = true
  }

  void 'request_end closes context reports attacks and publishes event'() {
    AppSecEvent event = Mock()
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.requestHeaders >> ['accept': ['header_value']]
    mockAppSecCtx.responseHeaders >> [
      'some-header' : ['123'],
      'content-type': ['text/html; charset=UTF-8']]
    RequestContext mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)

    when:
    def flow = requestEndedCB.apply(mockCtx, spanInfo)

    then:
    1 * spanInfo.getTags() >> TagMap.fromMap(['http.client_ip': '1.1.1.1'])
    1 * mockAppSecCtx.transferCollectedEvents() >> [event]
    1 * mockAppSecCtx.peerAddress >> '2001::1'
    1 * mockAppSecCtx.close()
    1 * traceSegment.setTagTop("_dd.appsec.enabled", 1)
    1 * traceSegment.setTagTop("_dd.runtime_family", "jvm")
    1 * traceSegment.setTagTop('appsec.event', true)
    1 * traceSegment.setDataTop('appsec', new AppSecEventWrapper([event]))
    1 * traceSegment.setTagTop('http.request.headers.accept', 'header_value')
    1 * traceSegment.setTagTop('http.response.headers.content-type', 'text/html; charset=UTF-8')
    1 * traceSegment.setTagTop('network.client.ip', '2001::1')
    1 * mockAppSecCtx.isWafBlocked()
    1 * mockAppSecCtx.hasWafErrors()
    1 * mockAppSecCtx.getWafTimeouts()
    1 * mockAppSecCtx.isWafRequestBlockFailure()
    1 * mockAppSecCtx.isWafRateLimited()
    1 * mockAppSecCtx.isWafTruncated()
    1 * wafMetricCollector.wafRequest(_, _, _, _, _, _, _) // call waf request metric
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'actor ip calculated from headers'() {
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.requestHeaders >> [
      'x-real-ip': ['10.0.0.1'],
      forwarded  : ['for=127.0.0.1', 'for="[::1]", for=8.8.8.8'],
    ]
    RequestContext mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)

    when:
    requestEndedCB.apply(mockCtx, spanInfo)

    then:
    1 * mockAppSecCtx.transferCollectedEvents() >> [Stub(AppSecEvent)]
    1 * spanInfo.getTags() >> TagMap.fromMap(['http.client_ip': '8.8.8.8'])
    1 * traceSegment.setTagTop('actor.ip', '8.8.8.8')
  }

  void 'bridge can collect headers'() {
    when:
    reqHeaderCB.accept(ctx, 'header1', 'value 1.1')
    reqHeaderCB.accept(ctx, 'header1', 'value 1.2')
    reqHeaderCB.accept(ctx, 'Header1', 'value 1.3')
    reqHeaderCB.accept(ctx, 'header2', 'value 2')
    respHeaderCB.accept(ctx, 'header3', 'value 3.1')
    respHeaderCB.accept(ctx, 'header3', 'value 3.2')
    respHeaderCB.accept(ctx, 'header3', 'value 3.3')
    respHeaderCB.accept(ctx, 'header4', 'value 4')

    then:
    def reqHeaders = ctx.data.requestHeaders
    assert reqHeaders['header1'] == ['value 1.1', 'value 1.2', 'value 1.3']
    assert reqHeaders['header2'] == ['value 2']
    def respHeaders = ctx.data.responseHeaders
    assert respHeaders['header3'] == ['value 3.1', 'value 3.2', 'value 3.3']
    assert respHeaders['header4'] == ['value 4']
  }

  void 'headers are split between cookies and non cookies'() {
    when:
    reqHeaderCB.accept(ctx, 'Cookie', 'foo=bar;foo2=bar2')
    reqHeaderCB.accept(ctx, 'Cookie', 'foo3=bar3')
    reqHeaderCB.accept(ctx, 'Another-Header', 'another value')

    then:
    def collectedHeaders = ctx.data.requestHeaders
    assert collectedHeaders['another-header'] == ['another value']
    assert !collectedHeaders.containsKey('cookie')

    def cookies = ctx.data.cookies
    assert cookies['foo'] == ['bar']
    assert cookies['foo2'] == ['bar2']
    assert cookies['foo3'] == ['bar3']
  }

  void 'headers provided after headers ended are ignored'() {
    DataBundle bundle

    when:
    ctx.data.rawURI = '/'
    ctx.data.peerAddress = '0.0.0.0'
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; NoopFlow.INSTANCE
    }

    and:
    reqHeadersDoneCB.apply(ctx)
    reqHeaderCB.accept(ctx, 'header', 'value')

    then:
    thrown(IllegalStateException)
    def data = bundle.get(KnownAddresses.HEADERS_NO_COOKIES)
    assert data == null || data.isEmpty()
  }

  void 'the socket address is distributed'() {
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    and:
    reqHeadersDoneCB.apply(ctx)
    requestMethodURICB.apply(ctx, 'GET', TestURIDataAdapter.create('/a'))
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_CLIENT_IP) == '0.0.0.0'
    bundle.get(KnownAddresses.REQUEST_CLIENT_PORT) == 5555
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'the inferred ip address is distributed if published before the socket address'() {
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    and:
    reqHeadersDoneCB.apply(ctx)
    requestMethodURICB.apply(ctx, 'GET', TestURIDataAdapter.create('/a'))
    requestInferredAddressCB.apply(ctx, '1.2.3.4')
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_INFERRED_CLIENT_IP) == '1.2.3.4'
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'setting headers then request uri triggers initial data event'() {
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    and:
    reqHeadersDoneCB.apply(ctx)
    requestMethodURICB.apply(ctx, 'GET', TestURIDataAdapter.create('/a'))
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_URI_RAW) == '/a'
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'the raw request uri is provided and decoded'() {
    DataBundle bundle
    GatewayContext gatewayContext
    def adapter = TestURIDataAdapter.create(uri, supportsRaw)

    when:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_URI_RAW in it
    }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    and:
    requestMethodURICB.apply(ctx, 'GET', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_URI_RAW) == expected
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false

    if (null != uri) {
      def query = bundle.get(KnownAddresses.REQUEST_QUERY)
      assert query['foo'] == ['bar 1', 'bar 2']
      assert query['xpto'] == ['']
    }

    where:
    uri                                    | supportsRaw | expected
    '/foo%6f?foo=bar+1&fo%6f=b%61r+2&xpto' | true        | '/foo%6f?foo=bar+1&fo%6f=b%61r+2&xpto'
    '/fooo?foo=bar 1&foo=bar 2&xpto'       | false       | '/fooo?foo=bar%201&foo=bar%202&xpto'
    null                                   | false       | ''
  }

  void 'exercise all decoding paths'() {
    DataBundle bundle
    GatewayContext gatewayContext
    String uri = "/?foo=$encoded"
    def adapter = TestURIDataAdapter.create(uri)

    when:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_URI_RAW in it
    }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    and:
    requestMethodURICB.apply(ctx, 'GET', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 80)

    then:
    def query = bundle.get(KnownAddresses.REQUEST_QUERY)
    query['foo'] == [decoded]
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false

    where:
    encoded  | decoded
    '%80'    | '\uFFFD' // repl. char: not a valid UTF-8 code unit sequence
    '%8'     | '%8'
    '%8G'    | '%8G'
    '%G8'    | '%G8'
    '%G8'    | '%G8'
    '%0:'    | '%0:'
    '%0A'    | '\n'
    '%0a'    | '\n'
    '%C2%80' | '\u0080'
  }

  void 'path params are published'() {
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_PATH_PARAMS in it
    }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    and:
    pathParamsCB.apply(ctx, [a: 'b'])

    then:
    bundle.get(KnownAddresses.REQUEST_PATH_PARAMS) == [a: 'b']
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'path params is not published twice'() {
    Flow flow

    setup:
    pathParamsCB.apply(ctx, [a: 'b'])

    when:
    flow = pathParamsCB.apply(ctx, [c: 'd'])

    then:
    flow == NoopFlow.INSTANCE
    0 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_PATH_PARAMS)
    0 * eventDispatcher.publishDataEvent(*_)
  }

  void callInitAndCaptureCBs() {
    // force all callbacks to be registered
    _ * eventDispatcher.allSubscribedDataAddresses() >> [KnownAddresses.REQUEST_PATH_PARAMS, KnownAddresses.REQUEST_BODY_OBJECT]

    1 * ig.registerCallback(EVENTS.requestStarted(), _) >> {
      requestStartedCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestEnded(), _) >> {
      requestEndedCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestMethodUriRaw(), _) >> {
      requestMethodURICB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestPathParams(), _) >> {
      pathParamsCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestHeader(), _) >> {
      reqHeaderCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestHeaderDone(), _) >> {
      reqHeadersDoneCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestClientSocketAddress(), _) >> {
      requestSocketAddressCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestInferredClientAddress(), _) >> {
      requestInferredAddressCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestBodyStart(), _) >> {
      requestBodyStartCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestBodyDone(), _) >> {
      requestBodyDoneCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestBodyProcessed(), _) >> {
      requestBodyProcessedCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.responseBody(), _) >> {
      responseBodyCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.responseStarted(), _) >> {
      responseStartedCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.responseHeader(), _) >> {
      respHeaderCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.responseHeaderDone(), _) >> {
      respHeadersDoneCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.grpcServerMethod(), _) >> {
      grpcServerMethodCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.grpcServerRequestMessage(), _) >> {
      grpcServerRequestMessageCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.graphqlServerRequestMessage(), _) >> {
      graphqlServerRequestMessageCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.databaseConnection(), _) >> {
      databaseConnectionCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.databaseSqlQuery(), _) >> {
      databaseSqlQueryCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.httpClientRequest(), _) >> {
      httpClientRequestCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.httpClientResponse(), _) >> {
      httpClientResponseCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.httpClientSampling(), _) >> {
      httpClientSamplingCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.fileLoaded(), _) >> {
      fileLoadedCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.requestSession(), _) >> {
      requestSessionCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.execCmd(), _) >> {
      execCmdCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.shellCmd(), _) >> {
      shellCmdCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.user(), _) >> {
      userCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.loginEvent(), _) >> {
      loginEventCB = it[1]; null
    }
    1 * ig.registerCallback(EVENTS.httpRoute(), _) >> {
      httpRouteCB = it[1]; null
    }
    0 * ig.registerCallback(_, _)

    bridge.init()
  }

  private static abstract class TestURIDataAdapter extends URIDataAdapterBase {

    static URIDataAdapter create(String uri, boolean supportsRaw = true) {
      if (supportsRaw) {
        new TestRawAdapter(uri)
      } else {
        new TestNoRawAdapter(uri)
      }
    }

    private final String p
    private final String q
    private final String scheme
    private final String host
    private final int port

    protected TestURIDataAdapter(String uri) {
      if (null == uri) {
        p = null
        q = null
        scheme = null
        host = null
        port = 0
      } else {
        def parts = uri.split("\\?")
        p = parts[0]
        q = parts.length == 2 ? parts[1] : null
        scheme = ((uri =~ /\A.+(?=:\/\/)/) ?: [null])[0]
        host = ((uri =~ /(?<=:\/\/).+(?=:|\/)/) ?: [null])[0]
        def m = uri =~ /(?<=:)\d+(?=\/|\z)/
        port = m ? m[0] as int : (scheme == 'http' ? 80 : 443)
      }
    }

    @Override
    String scheme() {
      scheme
    }

    @Override
    String host() {
      host
    }

    @Override
    int port() {
      port
    }

    @Override
    String path() {
      supportsRaw() ? null : p
    }

    @Override
    String fragment() {
      null
    }

    @Override
    String query() {
      supportsRaw() ? null : q
    }

    @Override
    String rawPath() {
      supportsRaw() ? p : null
    }

    @Override
    boolean hasPlusEncodedSpaces() {
      false
    }

    @Override
    String rawQuery() {
      supportsRaw() ? q : null
    }

    private static class TestRawAdapter extends TestURIDataAdapter {
      TestRawAdapter(String uri) {
        super(uri)
      }

      @Override
      boolean supportsRaw() {
        true
      }
    }

    private static class TestNoRawAdapter extends TestURIDataAdapter {
      TestNoRawAdapter(String uri) {
        super(uri)
      }

      @Override
      boolean supportsRaw() {
        false
      }
    }
  }

  void 'forwards request body start events and stores the supplier'() {
    StoredBodySupplier supplier = Stub()

    setup:
    supplier.get() >> 'foobar'

    expect:
    ctx.data.storedRequestBody == null

    when:
    requestBodyStartCB.apply(ctx, supplier)

    then:
    ctx.data.storedRequestBody == 'foobar'
  }

  void 'forwards request body done events and distributes the body contents'() {
    DataBundle bundle
    GatewayContext gatewayContext
    StoredBodySupplier supplier = Stub()

    setup:
    supplier.get() >> 'foobar'
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_BODY_RAW in it
    }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    when:
    requestBodyDoneCB.apply(ctx, supplier)

    then:
    bundle.get(KnownAddresses.REQUEST_BODY_RAW) == 'foobar'
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'request body does not get published twice'() {
    StoredBodySupplier supplier = Stub()
    Flow flow

    given:
    supplier.get() >> 'foobar'

    when:
    ctx.data.setRawReqBodyPublished(true)
    flow = requestBodyDoneCB.apply(ctx, supplier)

    then:
    flow == NoopFlow.INSTANCE
    0 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_BODY_RAW)
  }

  void 'forward request body processed'() {
    DataBundle bundle
    GatewayContext gatewayContext
    Object obj = 'hello'

    setup:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_BODY_OBJECT in it
    }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext)
    >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    when:
    requestBodyProcessedCB.apply(ctx, obj)

    then:
    bundle.get(KnownAddresses.REQUEST_BODY_OBJECT) == 'hello'
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'processed body does not published twice'() {
    Flow flow

    when:
    ctx.data.setConvertedReqBodyPublished(true)
    flow = requestBodyProcessedCB.apply(ctx, new Object())

    then:
    flow == NoopFlow.INSTANCE
    0 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_BODY_OBJECT)
    0 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false)
  }

  void 'request body transforms object and publishes'() {
    setup:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_BODY_OBJECT in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    Flow<?> flow = requestBodyProcessedCB.apply(ctx, new Object() {
      @SuppressWarnings('UnusedPrivateField')
      private String foo = 'bar'
    })

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.REQUEST_BODY_OBJECT) == [foo: 'bar']
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'forwards request method'() {
    DataBundle bundle
    GatewayContext gatewayContext
    def adapter = TestURIDataAdapter.create('http://example.com/')

    setup:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_METHOD in it
    }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    when:
    requestMethodURICB.apply(ctx, 'POST', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_METHOD) == 'POST'
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'scheme is extracted from the uri adapter'() {
    DataBundle bundle
    GatewayContext gatewayContext
    def adapter = TestURIDataAdapter.create('https://example.com/')

    when:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.REQUEST_SCHEME in it
    }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      bundle = it[2]; gatewayContext = it[3]; NoopFlow.INSTANCE
    }

    and:
    requestMethodURICB.apply(ctx, 'GET', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_SCHEME) == 'https'
    gatewayContext.isTransient == false
    gatewayContext.isRasp == false
  }

  void 'request data does not published twice'() {
    AppSecRequestContext reqCtx = Stub()
    Flow flow1, flow2, flow3

    when:
    ctx.data.setReqDataPublished(true)
    flow1 = requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)
    flow2 = reqHeadersDoneCB.apply(ctx)
    flow3 = requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create('/a'))

    then:
    flow1 == NoopFlow.INSTANCE
    flow2 == NoopFlow.INSTANCE
    flow3 == NoopFlow.INSTANCE
    0 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_SCHEME)
    0 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, reqCtx, _ as DataBundle, false)
  }


  void 'response_start produces appsec context and publishes event'() {
    eventDispatcher.getDataSubscribers({
      KnownAddresses.RESPONSE_STATUS in it
    }) >> nonEmptyDsInfo

    when:
    Flow<AppSecRequestContext> flow1 = responseStartedCB.apply(ctx, 404)
    Flow<AppSecRequestContext> flow2 = respHeadersDoneCB.apply(ctx)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      NoopFlow.INSTANCE
    }
    flow1.result == null
    flow1.action == Flow.Action.Noop.INSTANCE
    flow2.result == null
    flow2.action == Flow.Action.Noop.INSTANCE
  }

  void 'grpc server message recv transforms object and publishes'() {
    setup:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    Flow<?> flow = grpcServerRequestMessageCB.apply(ctx, new Object() {
      @SuppressWarnings('UnusedPrivateField')
      private String foo = 'bar'
    })

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE) == [foo: 'bar']
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == true
    gatewayContext.isRasp == false
  }

  void 'grpc server method publishes'() {
    setup:
    eventDispatcher.getDataSubscribers(KnownAddresses.GRPC_SERVER_METHOD) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    Flow<?> flow = grpcServerMethodCB.apply(ctx, '/my.package.Greeter/SayHello')

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      args -> bundle = args[2]; gatewayContext = args[3]; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.GRPC_SERVER_METHOD) == '/my.package.Greeter/SayHello'
    gatewayContext != null
    gatewayContext.isTransient == true
    gatewayContext.isRasp == false
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'process database type'() {
    setup:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.DB_TYPE in it
    }) >> nonEmptyDsInfo

    when:
    databaseConnectionCB.accept(ctx, 'postgresql')

    then:
    arCtx.dbType == 'postgresql'
  }

  void 'process jdbc statement query object'() {
    setup:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.DB_SQL_QUERY in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    Flow<?> flow = databaseSqlQueryCB.apply(ctx, 'SELECT * FROM foo')

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.DB_SQL_QUERY) == 'SELECT * FROM foo'
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == true
    gatewayContext.isRasp == true
  }

  void 'process http client request sampling'() {
    setup:
    eventDispatcher.getDataSubscribers({
      KnownAddresses.IO_NET_URL in it
    }) >> nonEmptyDsInfo

    when:
    Flow<?> flow = httpClientSamplingCB.apply(ctx, 1L)

    then:
    1 * downstreamSampler.sampleHttpClientRequest(arCtx, 1L) >> {
      sampled
    }
    flow.result == sampled

    where:
    sampled << [true, false]
  }

  void 'process http client request'() {
    setup:
    final url = 'https://www.datadoghq.com/'
    final method = 'POST'
    final headers = ['X-AppSec-TEst': ['true']]
    final contentType = MediaType.parse('application/json')
    final body = new ByteArrayInputStream('{"hello": "World!"}'.bytes)
    eventDispatcher.getDataSubscribers({
      KnownAddresses.IO_NET_URL in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    final request = new HttpClientRequest(1L, url, method, headers)
    request.setBody(contentType, body)
    Flow<?> flow = httpClientRequestCB.apply(ctx, request)

    then:
    downstreamSampler.isSampled(arCtx, _ as long) >> {
      sampled
    }
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.size() == (sampled ? 4 : 3)
    bundle.get(KnownAddresses.IO_NET_URL) == url
    bundle.get(KnownAddresses.IO_NET_REQUEST_METHOD) == method
    bundle.get(KnownAddresses.IO_NET_REQUEST_HEADERS) == toLowerCaseHeaders(headers)
    if (sampled) {
      bundle.get(KnownAddresses.IO_NET_REQUEST_BODY) == ['Hello': 'World!']
    }
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == true
    gatewayContext.isRasp == true
    gatewayContext.raspRuleType == RuleType.SSRF_REQUEST

    where:
    sampled << [true, false]
  }

  void 'process http client response'() {
    setup:
    final status = 200
    final headers = ['X-AppSec-TEst': ['true']]
    final contentType = MediaType.parse('application/json')
    final body = new ByteArrayInputStream('{"hello": "World!"}'.bytes)
    eventDispatcher.getDataSubscribers({
      KnownAddresses.IO_NET_RESPONSE_STATUS in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    final response = new HttpClientResponse(1L, status, headers)
    response.setBody(contentType, body)
    Flow<?> flow = httpClientResponseCB.apply(ctx, response)

    then:
    downstreamSampler.isSampled(arCtx, _ as long) >> {
      sampled
    }
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.size() == (sampled ? 3 : 2)
    bundle.get(KnownAddresses.IO_NET_RESPONSE_STATUS) == Integer.toString(status)
    bundle.get(KnownAddresses.IO_NET_RESPONSE_HEADERS) == toLowerCaseHeaders(headers)
    if (sampled) {
      bundle.get(KnownAddresses.IO_NET_RESPONSE_BODY) == ['Hello': 'World!']
    }
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == true
    gatewayContext.isRasp == true
    gatewayContext.raspRuleType == RuleType.SSRF_RESPONSE

    where:
    sampled << [true, false]
  }

  void 'process file loaded'() {
    setup:
    final path = 'https://www.datadoghq.com/demo/file.txt'
    eventDispatcher.getDataSubscribers({
      KnownAddresses.IO_FS_FILE in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    Flow<?> flow = fileLoadedCB.apply(ctx, path)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.IO_FS_FILE) == path
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == true
    gatewayContext.isRasp == true
  }

  void 'process exec cmd'() {
    setup:
    final cmd = ['/bin/../usr/bin/reboot', '-f'] as String[]
    eventDispatcher.getDataSubscribers({
      KnownAddresses.EXEC_CMD in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    Flow<?> flow = execCmdCB.apply(ctx, cmd)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.EXEC_CMD) == cmd
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == true
    gatewayContext.isRasp == true
  }

  void 'process shell cmd'() {
    setup:
    final cmd = '$(cat /etc/passwd 1>&2 ; echo .)'
    eventDispatcher.getDataSubscribers({
      KnownAddresses.SHELL_CMD in it
    }) >> nonEmptyDsInfo
    DataBundle bundle
    GatewayContext gatewayContext

    when:
    Flow<?> flow = shellCmdCB.apply(ctx, cmd)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.SHELL_CMD) == cmd
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
    gatewayContext.isTransient == true
    gatewayContext.isRasp == true
  }

  void 'calls trace segment post processor'() {
    setup:
    AgentSpan span = Stub()

    when:
    requestEndedCB.apply(ctx, span)

    then:
    1 * pp.processTraceSegment(traceSegment, ctx.data, [])
  }

  void 'no appsec events if was not created request context in request_start event'() {
    RequestContext emptyCtx = new RequestContext() {
      final Object data = null
      BlockResponseFunction blockResponseFunction

      @Override
      Object getData(RequestContextSlot slot) {
        data
      }

      @Override
      final TraceSegment getTraceSegment() {
        GatewayBridgeSpecification.this.traceSegment
      }

      @Override
      def <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
        return null
      }

      @Override
      void close() throws IOException {}
    }

    StoredBodySupplier supplier = Stub()
    IGSpanInfo spanInfo = Stub(AgentSpan)
    Object obj = 'obj'

    when:
    // request start event doesn't happen and not create AppSecRequestContext
    def flowMethod = requestMethodURICB.apply(emptyCtx, 'GET', TestURIDataAdapter.create('/a'))
    def flowParams = pathParamsCB.apply(emptyCtx, [a: 'b'])
    def flowSock = requestSocketAddressCB.apply(emptyCtx, '0.0.0.0', 5555)
    reqHeaderCB.accept(emptyCtx, 'header_name', 'header_value')
    def flowHeadersDone = reqHeadersDoneCB.apply(emptyCtx)
    requestBodyStartCB.apply(emptyCtx, supplier)
    def flowBodyEnd = requestBodyDoneCB.apply(emptyCtx, supplier)
    def flowBodyProc = requestBodyProcessedCB.apply(emptyCtx, obj)
    def flowReqEnd = requestEndedCB.apply(emptyCtx, spanInfo)
    def appSecReqCtx = emptyCtx.getData(RequestContextSlot.APPSEC)

    then:
    appSecReqCtx == null
    flowMethod == NoopFlow.INSTANCE
    flowParams == NoopFlow.INSTANCE
    flowSock == NoopFlow.INSTANCE
    flowHeadersDone == NoopFlow.INSTANCE
    flowBodyEnd == NoopFlow.INSTANCE
    flowBodyProc == NoopFlow.INSTANCE
    flowReqEnd == NoopFlow.INSTANCE
    0 * _
  }

  void 'default request headers are always set when appsec is enabled'() {
    final mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.requestHeaders >> [
      'host'                             : ['localhost'],
      'accept'                           : ['text/plain'],
      'content-type'                     : ['application/json'],
      'user-agent'                       : ['mozilla'],
      'x-amzn-trace-id'                  : ['Root=1-65ae48bc-04fb551979979b6c57973027'],
      'cloudfront-viewer-ja3-fingerprint': ['e7d705a3286e19ea42f587b344ee6865'],
      'cf-ray'                           : ['230b030023ae2822-SJC'],
      'x-cloud-trace-context'            : ['105445aa7843bc8bf206b12000100000/1'],
      'x-appgw-trace-id'                 : ['ac882cd65a2712a0fe1289ec2bb6aee7'],
      'x-sigsci-requestid'               : ['55c24b96ca84c02201000001'],
      'x-sigsci-tags'                    : ['SQLI, XSS'],
      'akamai-user-risk'                 : ['uuid=913c4545-757b-4d8d-859d-e1361a828361;status=0'],
    ]
    mockAppSecCtx.responseHeaders >> [
      'content-type'  : ['text/plain'],
      'content-length': ['13'],
    ]
    final mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    final spanInfo = Mock(AgentSpan) {
      getTags() >> TagMap.fromMap(['http.route':'/'])
    }

    when:
    requestEndedCB.apply(mockCtx, spanInfo)

    then:
    1 * mockAppSecCtx.transferCollectedEvents() >> []
    0 * traceSegment.setTagTop('http.request.headers.host', _)
    1 * traceSegment.setTagTop('http.request.headers.accept', 'text/plain')
    1 * traceSegment.setTagTop('http.request.headers.content-type', 'application/json')
    1 * traceSegment.setTagTop('http.request.headers.user-agent', 'mozilla')
    1 * traceSegment.setTagTop('http.request.headers.x-amzn-trace-id', 'Root=1-65ae48bc-04fb551979979b6c57973027')
    1 * traceSegment.setTagTop('http.request.headers.cloudfront-viewer-ja3-fingerprint', 'e7d705a3286e19ea42f587b344ee6865')
    1 * traceSegment.setTagTop('http.request.headers.cf-ray', '230b030023ae2822-SJC')
    1 * traceSegment.setTagTop('http.request.headers.x-cloud-trace-context', '105445aa7843bc8bf206b12000100000/1')
    1 * traceSegment.setTagTop('http.request.headers.x-appgw-trace-id', 'ac882cd65a2712a0fe1289ec2bb6aee7')
    1 * traceSegment.setTagTop('http.request.headers.x-sigsci-requestid', '55c24b96ca84c02201000001')
    1 * traceSegment.setTagTop('http.request.headers.x-sigsci-tags', 'SQLI, XSS')
    1 * traceSegment.setTagTop('http.request.headers.akamai-user-risk', 'uuid=913c4545-757b-4d8d-859d-e1361a828361;status=0')
    1 * traceSegment.setTagTop('http.response.headers.content-type', 'text/plain')
    1 * traceSegment.setTagTop('http.response.headers.content-length', '13')
  }

  void 'request headers are always set when there are user tracking events'() {
    given:
    final mockAppSecCtx = Stub(AppSecRequestContext) {
      transferCollectedEvents() >> []
      getRequestHeaders() >> [
        'host': ['localhost']
      ]
      getResponseHeaders() >> [
        'content-type': ['text/plain']
      ]
    }
    final mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    final spanInfo = Stub(AgentSpan)
    traceSegment.getTagTop(tag) >> true

    when:
    requestEndedCB.apply(mockCtx, spanInfo)

    then:
    (userTracking ? 1 : 0) * traceSegment.setTagTop('http.request.headers.host', 'localhost')
    1 * traceSegment.setTagTop('http.response.headers.content-type', 'text/plain')

    where:
    tag                                       | userTracking
    'appsec.events.users.login.success.track' | true
    'appsec.events.users.login.failure.track' | true
    'appsec.another.unrelated.tag'            | false
  }

  void 'fingerprints are set in the span after a request'() {
    given:
    final mockAppSecCtx = new AppSecRequestContext()
    mockAppSecCtx.reportDerivatives(['_dd.appsec.fp.http.endpoint': ['value': 'xyz']])
    final mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    final spanInfo = Stub(AgentSpan)

    when:
    requestEndedCB.apply(mockCtx, spanInfo)

    then:
    1 * traceSegment.setTagTop('_dd.appsec.fp.http.endpoint', 'xyz')
  }

  void 'process session ids'() {
    setup:
    DataBundle bundle
    GatewayContext gatewayContext
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo
    final sessionId = UUID.randomUUID().toString()

    when:
    requestSessionCB.apply(ctx, sessionId)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, db, gw -> bundle = db; gatewayContext = gw; NoopFlow.INSTANCE
    }
    bundle.get(KnownAddresses.SESSION_ID) == sessionId
    gatewayContext.isTransient == false
  }

  void "test onUserEvent"() {
    setup:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo

    when:
    userCB.apply(ctx, USER_ID)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, DataBundle db, GatewayContext gw ->
      assert db.get(KnownAddresses.USER_ID) == USER_ID
      assert !gw.isTransient
      return NoopFlow.INSTANCE
    }

    when:
    userCB.apply(ctx, USER_ID)

    then: 'no call to the WAF for duplicated calls'
    0 * eventDispatcher.publishDataEvent
  }

  void "test onSignup"() {
    setup:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo

    when:
    loginEventCB.apply(ctx, SIGN_UP, USER_ID)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, DataBundle db, GatewayContext gw ->
      assert db.get(KnownAddresses.USER_LOGIN) == USER_ID
      assert db.get(KnownAddresses.SIGN_UP) != null
      assert !gw.isTransient
      return NoopFlow.INSTANCE
    }
  }

  void "test onLoginSuccess"() {
    setup:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo

    when:
    loginEventCB.apply(ctx, LOGIN_SUCCESS, USER_ID)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, DataBundle db, GatewayContext gw ->
      assert db.get(KnownAddresses.USER_LOGIN) == USER_ID
      assert db.get(KnownAddresses.LOGIN_SUCCESS) != null
      assert !gw.isTransient
      return NoopFlow.INSTANCE
    }
  }

  void "test onLoginFailure (#mode)"() {
    setup:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo

    when:
    loginEventCB.apply(ctx, LOGIN_FAILURE, USER_ID)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, _ as GatewayContext) >> {
      a, b, DataBundle db, GatewayContext gw ->
      assert db.get(KnownAddresses.USER_LOGIN) == USER_ID
      assert db.get(KnownAddresses.LOGIN_FAILURE) != null
      assert !gw.isTransient
      return NoopFlow.INSTANCE
    }
  }

  void 'test configuration updates should reset cached subscriptions'() {
    when:
    requestSessionCB.apply(ctx, UUID.randomUUID().toString())

    then:
    1 * eventDispatcher.getDataSubscribers(KnownAddresses.SESSION_ID) >> emptyDsInfo
    0 * eventDispatcher.publishDataEvent

    when:
    requestSessionCB.apply(ctx, UUID.randomUUID().toString())

    then:
    0 * eventDispatcher.getDataSubscribers
    0 * eventDispatcher.publishDataEvent

    when:
    bridge.reset()
    requestSessionCB.apply(ctx, UUID.randomUUID().toString())

    then:
    1 * eventDispatcher.getDataSubscribers(KnownAddresses.SESSION_ID) >> nonEmptyDsInfo
    1 * eventDispatcher.publishDataEvent(_, _, _, _)
  }

  void 'test api security sampling'() {
    given:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    RequestContext mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)
    when:
    def flow = requestEndedCB.apply(mockCtx, spanInfo)
    then:
    1 * mockAppSecCtx.transferCollectedEvents() >> []
    1 * spanInfo.getTags() >>  TagMap.fromMap(['http.route': 'route'])
    1 * requestSampler.preSampleRequest(_) >> true
    0 * traceSegment.setTagTop(Tags.ASM_KEEP, true)
    0 * traceSegment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM)
  }

  void 'test api security sampling - trace excluded'() {
    given:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    RequestContext mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)
    when:
    def flow = requestEndedCB.apply(mockCtx, spanInfo)
    then:
    1 * mockAppSecCtx.transferCollectedEvents() >> []
    1 * spanInfo.getTags() >>  TagMap.fromMap(['http.route': 'route'])
    1 * requestSampler.preSampleRequest(_) >> false
    0 * traceSegment.setTagTop(Tags.ASM_KEEP, true)
    0 * traceSegment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM)
  }

  void 'test api security sampling with tracing disabled'() {
    given:
    injectSysConfig(GeneralConfig.APM_TRACING_ENABLED, "false")
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    RequestContext mockCtx = Stub(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)
    when:
    def flow = requestEndedCB.apply(mockCtx, spanInfo)
    then:
    1 * mockAppSecCtx.transferCollectedEvents() >> []
    1 * spanInfo.getTags() >> TagMap.fromMap(['http.route': 'route'])
    1 * requestSampler.preSampleRequest(_) >> true
    1 * traceSegment.setTagTop(Tags.ASM_KEEP, true)
    1 * traceSegment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM)
  }


  void 'test default writeRequestHeaders'(){
    given:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    def allowedHeaders = ['x-allowed-header', 'x-multiple-allowed-header', 'x-always-included'] as Set
    def headers = [
      'x-allowed-header' : ['value1'],
      'x-multiple-allowed-header' : ['value1A', 'value1B'],
      'x-other-header-1' : ['value2'],
      'x-other-header-2' : ['value3'],
      'x-other-header-3' : ['value4']
    ]

    when:
    GatewayBridge.writeRequestHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, false)

    then:
    1 * traceSegment.setTagTop('http.request.headers.x-allowed-header', 'value1')
    1 * traceSegment.setTagTop('http.request.headers.x-multiple-allowed-header', 'value1A,value1B')
    0 * traceSegment.setTagTop(_, _)
  }

  void 'test default writeResponseHeaders'(){
    given:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    def allowedHeaders = ['x-allowed-header', 'x-multiple-allowed-header', 'x-always-included'] as Set
    def headers = [
      'x-allowed-header' : ['value1'],
      'x-multiple-allowed-header' : ['value1A', 'value1B'],
      'x-other-header-1' : ['value2'],
      'x-other-header-2' : ['value3'],
      'x-other-header-3' : ['value4']
    ]

    when:
    GatewayBridge.writeResponseHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, false)

    then:
    1 * traceSegment.setTagTop('http.response.headers.x-allowed-header', 'value1')
    1 * traceSegment.setTagTop('http.response.headers.x-multiple-allowed-header', 'value1A,value1B')
    0 * traceSegment.setTagTop(_, _)
  }

  void 'test  writeRequestHeaders collecting all headers '(){
    setup:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.isExtendedDataCollection() >> true
    mockAppSecCtx.getExtendedDataCollectionMaxHeaders() >> 4
    mockAppSecCtx.getCookies() >> new HashMap()

    def allowedHeaders = ['x-allowed-header', 'x-multiple-allowed-header', 'x-always-included'] as Set
    def headers = [
      'x-allowed-header' : ['value1'],
      'x-multiple-allowed-header' : ['value1A', 'value1B'],
      'x-other-header-1' : ['value2'],
      'x-other-header-2' : ['value3'],
      'x-other-header-3' : ['value4']
    ]

    when:
    GatewayBridge.writeRequestHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, true)

    then:
    1 * traceSegment.setTagTop('http.request.headers.x-allowed-header', 'value1')
    1 * traceSegment.setTagTop('http.request.headers.x-multiple-allowed-header', 'value1A,value1B')
    1 * traceSegment.setTagTop('http.request.headers.x-other-header-1', 'value2')
    1 * traceSegment.setTagTop('http.request.headers.x-other-header-2', 'value3')
    1 * traceSegment.setTagTop('_dd.appsec.request.header_collection.discarded', 1)
    0 * traceSegment.setTagTop(_, _)
  }

  void 'test  writeResponseHeaders collecting all headers '(){
    setup:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.isExtendedDataCollection() >> true
    mockAppSecCtx.getExtendedDataCollectionMaxHeaders() >> 4

    def allowedHeaders = ['x-allowed-header', 'x-multiple-allowed-header', 'x-always-included'] as Set
    def headers = [
      'x-allowed-header' : ['value1'],
      'x-multiple-allowed-header' : ['value1A', 'value1B'],
      'x-other-header-1' : ['value2'],
      'x-other-header-2' : ['value3'],
      'x-other-header-3' : ['value4']
    ]

    when:
    GatewayBridge.writeResponseHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, true)

    then:
    1 * traceSegment.setTagTop('http.response.headers.x-allowed-header', 'value1')
    1 * traceSegment.setTagTop('http.response.headers.x-multiple-allowed-header', 'value1A,value1B')
    1 * traceSegment.setTagTop('http.response.headers.x-other-header-1', 'value2')
    1 * traceSegment.setTagTop('http.response.headers.x-other-header-2', 'value3')
    1 * traceSegment.setTagTop('_dd.appsec.response.header_collection.discarded', 1)
    0 * traceSegment.setTagTop(_, _)
  }

  void 'test writeRequestHeaders redacts AUTHORIZATION_HEADERS values'() {
    setup:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.isExtendedDataCollection() >> true
    mockAppSecCtx.getExtendedDataCollectionMaxHeaders() >> 10
    mockAppSecCtx.getCookies() >> new HashMap()

    def allowedHeaders = ['content-type', 'user-agent'] as Set
    def headers = [
      'authorization': ['Bearer abc123xyz'],
      'proxy-authorization': ['Basic dXNlcjpwYXNz'],
      'authentication-info': ['nextnonce="abc123"'],
      'proxy-authentication-info': ['nextnonce="xyz789"'],
      'content-type': ['application/json'],
      'user-agent': ['Mozilla/5.0'],
      'accept': ['text/html,application/xhtml+xml']
    ]

    when:
    GatewayBridge.writeRequestHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, true)

    then:
    1 * traceSegment.setTagTop('http.request.headers.authorization', '<redacted>')
    1 * traceSegment.setTagTop('http.request.headers.proxy-authorization', '<redacted>')
    1 * traceSegment.setTagTop('http.request.headers.authentication-info', '<redacted>')
    1 * traceSegment.setTagTop('http.request.headers.proxy-authentication-info', '<redacted>')
    1 * traceSegment.setTagTop('http.request.headers.content-type', 'application/json')
    1 * traceSegment.setTagTop('http.request.headers.user-agent', 'Mozilla/5.0')
    1 * traceSegment.setTagTop('http.request.headers.accept', 'text/html,application/xhtml+xml')
    0 * traceSegment.setTagTop(_, _)
  }

  void 'test writeResponseHeaders redacts AUTHORIZATION_HEADERS values'() {
    setup:
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.isExtendedDataCollection() >> true
    mockAppSecCtx.getExtendedDataCollectionMaxHeaders() >> 10
    mockAppSecCtx.getCookies() >> new HashMap()

    def allowedHeaders = ['content-type', 'server'] as Set
    def headers = [
      'www-authenticate': ['Basic realm="example"'],
      'proxy-authenticate': ['Digest realm="example"'],
      'set-cookie': ['session=abc123; Path=/; HttpOnly'],
      'content-type': ['text/html; charset=UTF-8'],
      'server': ['nginx/1.18.0']
    ]

    when:
    GatewayBridge.writeResponseHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, true)

    then:
    1 * traceSegment.setTagTop('http.response.headers.www-authenticate', '<redacted>')
    1 * traceSegment.setTagTop('http.response.headers.proxy-authenticate', '<redacted>')
    1 * traceSegment.setTagTop('http.response.headers.set-cookie', '<redacted>')
    1 * traceSegment.setTagTop('http.response.headers.content-type', 'text/html; charset=UTF-8')
    1 * traceSegment.setTagTop('http.response.headers.server', 'nginx/1.18.0')
    0 * traceSegment.setTagTop(_, _)
  }

  void 'test on httpRoute'() {
    given:
    final route = 'dummy-route'

    when:
    httpRouteCB.accept(ctx, route)

    then:
    arCtx.getRoute() == route
  }

  void 'test on response body callback'() {
    when:
    responseBodyCB.apply(ctx, [test: 'this is a test'])

    then:
    1 * eventDispatcher.getDataSubscribers(KnownAddresses.RESPONSE_BODY_OBJECT) >> nonEmptyDsInfo
    1 * eventDispatcher.publishDataEvent(_, _, _, _) >> {
      final bundle = it[2] as DataBundle
      final body = bundle.get(KnownAddresses.RESPONSE_BODY_OBJECT)
      assert body['test'] == 'this is a test'
    }
  }

  static toLowerCaseHeaders(final Map<String, List<String>> headers) {
    return headers.collectEntries {
      [(it.key.toLowerCase(Locale.ROOT)): it.value]
    }
  }
}
