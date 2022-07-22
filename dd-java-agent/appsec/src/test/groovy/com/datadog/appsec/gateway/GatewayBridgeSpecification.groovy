package com.datadog.appsec.gateway

import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.SingletonDataBundle
import com.datadog.appsec.report.AppSecEventWrapper
import com.datadog.appsec.report.raw.events.AppSecEvent100
import datadog.trace.api.function.Function
import datadog.trace.api.TraceSegment
import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.api.time.TimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.gateway.Events.EVENTS

class GatewayBridgeSpecification extends DDSpecification {
  SubscriptionService ig = Mock()
  EventDispatcher eventDispatcher = Mock()
  AppSecRequestContext arCtx = new AppSecRequestContext()
  TraceSegment traceSegment = Mock()
  RequestContext<AppSecRequestContext> ctx = new RequestContext<AppSecRequestContext>() {
    final AppSecRequestContext data = arCtx

    @Override
    final TraceSegment getTraceSegment() {
      GatewayBridgeSpecification.this.traceSegment
    }
  }
  EventProducerService.DataSubscriberInfo nonEmptyDsInfo = {
    EventProducerService.DataSubscriberInfo i = Mock()
    i.empty >> false
    i
  }()

  RateLimiter rateLimiter = new RateLimiter(10, { -> 0L } as TimeSource, RateLimiter.ThrottledCallback.NOOP)
  TraceSegmentPostProcessor pp = Mock()
  GatewayBridge bridge = new GatewayBridge(ig, eventDispatcher, rateLimiter, [pp])

  Supplier<Flow<AppSecRequestContext>> requestStartedCB
  BiFunction<RequestContext, AgentSpan, Flow<Void>> requestEndedCB
  TriConsumer<RequestContext, String, String> reqHeaderCB
  Function<RequestContext, Flow<Void>> reqHeadersDoneCB
  TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> requestMethodURICB
  BiFunction<RequestContext, Map<String, Object>, Flow<Void>> pathParamsCB
  TriFunction<RequestContext, String, Integer, Flow<Void>> requestSocketAddressCB
  BiFunction<RequestContext, StoredBodySupplier, Void> requestBodyStartCB
  BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestBodyDoneCB
  BiFunction<RequestContext, Object, Flow<Void>> requestBodyProcessedCB
  BiFunction<RequestContext, Integer, Flow<Void>> responseStartedCB
  TriConsumer<RequestContext, String, String> respHeaderCB
  Function<RequestContext, Flow<Void>> respHeadersDoneCB
  BiFunction<RequestContext, Object, Flow<Void>> grpcServerRequestMessageCB

  void setup() {
    callInitAndCaptureCBs()
  }

  void 'request_start produces appsec context and publishes event'() {
    when:
    Flow<AppSecRequestContext> startFlow = requestStartedCB.get()

    then:
    1 * eventDispatcher.publishEvent(_ as AppSecRequestContext, EventType.REQUEST_START)
    Object producedCtx = startFlow.getResult()
    producedCtx instanceof AppSecRequestContext
    startFlow.action == Flow.Action.Noop.INSTANCE
  }

  void 'request_end closes context reports attacks and publishes event'() {
    AppSecEvent100 event = Mock()
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.requestHeaders >> ['accept':['header_value']]
    mockAppSecCtx.responseHeaders >> [
      'some-header': ['123'],
      'content-type':['text/html; charset=UTF-8']]
    RequestContext mockCtx = Mock(RequestContext) {
      getData() >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)

    when:
    def flow = requestEndedCB.apply(mockCtx, spanInfo)

    then:
    1 * spanInfo.getTags() >> ['http.client_ip':'1.1.1.1']
    1 * mockAppSecCtx.transferCollectedEvents() >> [event]
    1 * mockAppSecCtx.peerAddress >> '2001::1'
    1 * mockAppSecCtx.close()
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop("_dd.appsec.enabled", 1)
    1 * traceSegment.setTagTop("_dd.runtime_family", "jvm")
    1 * traceSegment.setTagTop('appsec.event', true)
    1 * traceSegment.setDataTop('appsec', new AppSecEventWrapper([event]))
    1 * traceSegment.setTagTop('http.request.headers.accept', 'header_value')
    1 * traceSegment.setTagTop('http.response.headers.content-type', 'text/html; charset=UTF-8')
    1 * traceSegment.setTagTop('network.client.ip', '2001::1')
    1 * traceSegment._(*_)
    1 * eventDispatcher.publishEvent(mockAppSecCtx, EventType.REQUEST_END)
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'event publishing is rate limited'() {
    AppSecEvent100 event = Mock()
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.requestHeaders >> [:]
    RequestContext mockCtx = Mock(RequestContext) {
      getData() >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)

    when:
    11.times {requestEndedCB.apply(mockCtx, spanInfo) }

    then:
    11 * mockAppSecCtx.transferCollectedEvents() >> [event]
    11 * mockAppSecCtx.close()
    11 * eventDispatcher.publishEvent(mockAppSecCtx, EventType.REQUEST_END)
    10 * spanInfo.getTags() >> ['http.client_ip':'1.1.1.1']
    10 * traceSegment.setDataTop("appsec", _)
  }

  void 'actor ip calculated from headers'() {
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    mockAppSecCtx.requestHeaders >> [
      'x-real-ip': ['10.0.0.1'],
      forwarded: ['for=127.0.0.1', 'for="[::1]", for=8.8.8.8'],
    ]
    RequestContext mockCtx = Mock(RequestContext) {
      getData() >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock(AgentSpan)

    when:
    requestEndedCB.apply(mockCtx, spanInfo)

    then:
    1 * mockAppSecCtx.transferCollectedEvents() >> [Mock(AppSecEvent100)]
    1 * spanInfo.getTags() >> ['http.client_ip':'8.8.8.8']
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
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    reqHeadersDoneCB.apply(ctx)
    reqHeaderCB.accept(ctx, 'header', 'value')

    then:
    thrown(IllegalStateException)
    assert bundle.get(KnownAddresses.HEADERS_NO_COOKIES).isEmpty()
  }

  void 'the socket address is distributed'() {
    DataBundle bundle

    when:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    reqHeadersDoneCB.apply(ctx)
    requestMethodURICB.apply(ctx, 'GET', TestURIDataAdapter.create('/a'))
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_CLIENT_IP) == '0.0.0.0'
    bundle.get(KnownAddresses.REQUEST_CLIENT_PORT) == 5555
  }

  void 'setting headers then request uri triggers initial data event'() {
    DataBundle bundle

    when:
    eventDispatcher.getDataSubscribers(_) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    reqHeadersDoneCB.apply(ctx)
    requestMethodURICB.apply(ctx, 'GET', TestURIDataAdapter.create('/a'))
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_URI_RAW) == '/a'
  }

  void 'the raw request uri is provided and decoded'() {
    DataBundle bundle
    def adapter = TestURIDataAdapter.create(uri, supportsRaw)

    when:
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_URI_RAW in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    requestMethodURICB.apply(ctx, 'GET', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    assert bundle.get(KnownAddresses.REQUEST_URI_RAW) == expected

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
    String uri = "/?foo=$encoded"
    def adapter = TestURIDataAdapter.create(uri)

    when:
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_URI_RAW in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    requestMethodURICB.apply(ctx, 'GET', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 80)

    then:
    def query = bundle.get(KnownAddresses.REQUEST_QUERY)
    assert query['foo'] == [decoded]

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

    when:
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_PATH_PARAMS in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    pathParamsCB.apply(ctx, [a: 'b'])

    then:
    assert bundle.get(KnownAddresses.REQUEST_PATH_PARAMS) == [a: 'b']
  }

  void 'path params is not published twice'() {
    Flow flow

    when:
    arCtx.addAll(new SingletonDataBundle(KnownAddresses.REQUEST_PATH_PARAMS, [a: 'b']))
    flow = pathParamsCB.apply(ctx, [c: 'd'])

    then:
    flow == NoopFlow.INSTANCE
    0 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_PATH_PARAMS)
    0 * eventDispatcher.publishDataEvent(*_)
  }

  void callInitAndCaptureCBs() {
    // force all callbacks to be registered
    _ * eventDispatcher.allSubscribedEvents() >> [EventType.REQUEST_BODY_START, EventType.REQUEST_BODY_END]
    _ * eventDispatcher.allSubscribedDataAddresses() >> [KnownAddresses.REQUEST_PATH_PARAMS, KnownAddresses.REQUEST_BODY_OBJECT]

    1 * ig.registerCallback(EVENTS.requestStarted(), _) >> { requestStartedCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestEnded(), _) >> { requestEndedCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestMethodUriRaw(), _) >> { requestMethodURICB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestPathParams(), _) >> { pathParamsCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestHeader(), _) >> { reqHeaderCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestHeaderDone(), _) >> { reqHeadersDoneCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestClientSocketAddress(), _) >> { requestSocketAddressCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestBodyStart(), _) >> { requestBodyStartCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestBodyDone(), _) >> { requestBodyDoneCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestBodyProcessed(), _) >> { requestBodyProcessedCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.responseStarted(), _) >> { responseStartedCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.responseHeader(), _) >> { respHeaderCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.responseHeaderDone(), _) >> { respHeadersDoneCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.grpcServerRequestMessage(), _) >> { grpcServerRequestMessageCB = it[1]; null }
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
    StoredBodySupplier supplier = Mock()

    setup:
    supplier.get() >> 'foobar'

    expect:
    ctx.data.storedRequestBody == null

    when:
    requestBodyStartCB.apply(ctx, supplier)

    then:
    1 * eventDispatcher.publishEvent(ctx.data, EventType.REQUEST_BODY_START)
    ctx.data.storedRequestBody == 'foobar'
  }

  void 'forwards request body done events and distributes the body contents'() {
    DataBundle bundle
    StoredBodySupplier supplier = Mock()

    setup:
    supplier.get() >> 'foobar'
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_BODY_RAW in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    when:
    requestBodyDoneCB.apply(ctx, supplier)

    then:
    1 * eventDispatcher.publishEvent(ctx.data, EventType.REQUEST_BODY_END)
    bundle.get(KnownAddresses.REQUEST_BODY_RAW) == 'foobar'
  }

  void 'request body does not published twice'() {
    StoredBodySupplier supplier = Mock()
    Flow flow

    given:
    supplier.get() >> 'foobar'

    when:
    ctx.data.setRawReqBodyPublished(true)
    flow = requestBodyDoneCB.apply(ctx, supplier)

    then:
    flow == NoopFlow.INSTANCE
    0 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_BODY_RAW)
    0 * eventDispatcher.publishEvent(ctx.data, EventType.REQUEST_BODY_END)
  }

  void 'forward request body processed'() {
    DataBundle bundle
    Object obj = 'hello'

    setup:
    eventDispatcher.getDataSubscribers({KnownAddresses.REQUEST_BODY_OBJECT in it}) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >> { bundle = it[2]; NoopFlow.INSTANCE }

    when:
    requestBodyProcessedCB.apply(ctx, obj)

    then:
    bundle.get(KnownAddresses.REQUEST_BODY_OBJECT) == 'hello'
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
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_BODY_OBJECT in it }) >> nonEmptyDsInfo
    DataBundle bundle

    when:
    Flow<?> flow = requestBodyProcessedCB.apply(ctx, new Object() {
        @SuppressWarnings('UnusedPrivateField')
        private String foo = 'bar'
      })

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { a, b, db, c -> bundle = db; NoopFlow.INSTANCE }
    bundle.get(KnownAddresses.REQUEST_BODY_OBJECT) == [foo: 'bar']
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'forwards request method'() {
    DataBundle bundle
    def adapter = TestURIDataAdapter.create('http://example.com/')

    setup:
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_METHOD in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    when:
    requestMethodURICB.apply(ctx, 'POST', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_METHOD) == 'POST'
  }

  void 'scheme is extracted from the uri adapter'() {
    DataBundle bundle
    def adapter = TestURIDataAdapter.create('https://example.com/')

    when:
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_SCHEME in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    requestMethodURICB.apply(ctx, 'GET', adapter)
    reqHeadersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_SCHEME) == 'https'
  }

  void 'request data does not published twice'() {
    AppSecRequestContext reqCtx = Mock()
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
    eventDispatcher.getDataSubscribers({ KnownAddresses.RESPONSE_STATUS in it }) >> nonEmptyDsInfo

    when:
    Flow<AppSecRequestContext> flow1 = responseStartedCB.apply(ctx, 404)
    Flow<AppSecRequestContext> flow2 = respHeadersDoneCB.apply(ctx)

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { NoopFlow.INSTANCE }
    flow1.result == null
    flow1.action == Flow.Action.Noop.INSTANCE
    flow2.result == null
    flow2.action == Flow.Action.Noop.INSTANCE
  }

  void 'grpc server message recv transforms object and publishes'() {
    setup:
    eventDispatcher.getDataSubscribers({ KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE in it }) >> nonEmptyDsInfo
    DataBundle bundle

    when:
    Flow<?> flow = grpcServerRequestMessageCB.apply(ctx, new Object() {
        @SuppressWarnings('UnusedPrivateField')
        private String foo = 'bar'
      })

    then:
    1 * eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, true) >>
    { a, b, db, c -> bundle = db; NoopFlow.INSTANCE }
    bundle.get(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE) == [foo: 'bar']
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'calls trace segment post processor'() {
    setup:
    AgentSpan span = Mock()

    when:
    requestEndedCB.apply(ctx, span)

    then:
    1 * pp.processTraceSegment(traceSegment, ctx.data, [])
  }
}
