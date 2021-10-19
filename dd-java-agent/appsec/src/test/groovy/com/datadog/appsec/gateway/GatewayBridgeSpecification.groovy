package com.datadog.appsec.gateway

import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.StringKVPair
import com.datadog.appsec.report.InbandReportService
import com.datadog.appsec.report.ReportService
import com.datadog.appsec.report.raw.events.attack.Attack010
import datadog.trace.api.Function
import datadog.trace.api.function.BiConsumer
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
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.gateway.Events.EVENTS

class GatewayBridgeSpecification extends DDSpecification {
  SubscriptionService ig = Mock()
  EventDispatcher eventDispatcher = Mock()
  ReportService reportService = Mock()
  InbandReportService inbandReportService = Mock()
  AppSecRequestContext arCtx = new AppSecRequestContext()
  TraceSegment traceSegment = Mock()
  RequestContext<AppSecRequestContext> ctx = new RequestContext<AppSecRequestContext>() {
    @Override
    AppSecRequestContext getData() {
      return arCtx
    }

    @Override
    TraceSegment getTraceSegment() {
      return traceSegment
    }
  }
  EventProducerService.DataSubscriberInfo nonEmptyDsInfo = {
    EventProducerService.DataSubscriberInfo i = Mock()
    i.empty >> false
    i
  }()

  GatewayBridge bridge = new GatewayBridge(ig, eventDispatcher, reportService, inbandReportService)

  Supplier<Flow<AppSecRequestContext>> requestStartedCB
  BiFunction<RequestContext, AgentSpan, Flow<Void>> requestEndedCB
  TriConsumer<RequestContext, String, String> headerCB
  Function<RequestContext, Flow<Void>> headersDoneCB
  TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> requestMethodURICB
  TriFunction<RequestContext, String, Integer, Flow<Void>> requestSocketAddressCB
  BiFunction<RequestContext, StoredBodySupplier, Void> requestBodyStartCB
  BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestBodyDoneCB
  BiConsumer<RequestContext, Integer> responseStartedCB

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
    Attack010 attack = Mock()
    AppSecRequestContext mockAppSecCtx = Mock(AppSecRequestContext)
    RequestContext mockCtx = Mock(RequestContext) {
      getData() >> mockAppSecCtx
      getTraceSegment() >> traceSegment
    }
    IGSpanInfo spanInfo = Mock()

    when:
    def flow = requestEndedCB.apply(mockCtx, spanInfo)

    then:
    1 * mockAppSecCtx.transferCollectedAttacks() >> [attack]
    1 * mockAppSecCtx.close()
    1 * reportService.reportAttack(attack)
    1 * inbandReportService.reportAttacks([attack], traceSegment)
    1 * eventDispatcher.publishEvent(mockAppSecCtx, EventType.REQUEST_END)
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'bridge can collect headers'() {
    when:
    headerCB.accept(ctx, 'header1', 'value 1.1')
    headerCB.accept(ctx, 'header1', 'value 1.2')
    headerCB.accept(ctx, 'Header1', 'value 1.3')
    headerCB.accept(ctx, 'header2', 'value 2')

    then:
    def headers = ctx.data.collectedHeaders
    assert headers['header1'] == ['value 1.1', 'value 1.2', 'value 1.3']
    assert headers['header2'] == ['value 2']
  }

  void 'headers are split between cookies and non cookies'() {
    when:
    headerCB.accept(ctx, 'Cookie', 'foo=bar;foo2=bar2')
    headerCB.accept(ctx, 'Cookie', 'foo3=bar3')
    headerCB.accept(ctx, 'Another-Header', 'another value')

    then:
    def collectedHeaders = ctx.data.collectedHeaders
    assert collectedHeaders['another-header'] == ['another value']
    assert !collectedHeaders.containsKey('cookie')

    def cookies = ctx.data.collectedCookies
    assert cookies.contains(new StringKVPair('foo', 'bar'))
    assert cookies.contains(new StringKVPair('foo2', 'bar2'))
    assert cookies.contains(new StringKVPair('foo3', 'bar3'))
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
    headersDoneCB.apply(ctx)
    headerCB.accept(ctx, 'header', 'value')

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
    headersDoneCB.apply(ctx)
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
    headersDoneCB.apply(ctx)
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
    headersDoneCB.apply(ctx)
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
    headersDoneCB.apply(ctx)
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

  void callInitAndCaptureCBs() {
    // force all callbacks to be registered
    1 * eventDispatcher.allSubscribedEvents() >> [EventType.REQUEST_BODY_START, EventType.REQUEST_BODY_END]
    1 * eventDispatcher.allSubscribedDataAddresses() >> []

    1 * ig.registerCallback(EVENTS.requestStarted(), _) >> { requestStartedCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestEnded(), _) >> { requestEndedCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestMethodUriRaw(), _) >> { requestMethodURICB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestHeader(), _) >> { headerCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestHeaderDone(), _) >> { headersDoneCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestClientSocketAddress(), _) >> { requestSocketAddressCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestBodyStart(), _) >> { requestBodyStartCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.requestBodyDone(), _) >> { requestBodyDoneCB = it[1]; null }
    1 * ig.registerCallback(EVENTS.responseStarted(), _) >> { responseStartedCB = it[1]; null }
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

  void 'forwards request method'() {
    DataBundle bundle
    def adapter = TestURIDataAdapter.create('http://example.com/')

    setup:
    eventDispatcher.getDataSubscribers({ KnownAddresses.REQUEST_METHOD in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx.data, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    when:
    requestMethodURICB.apply(ctx, 'POST', adapter)
    headersDoneCB.apply(ctx)
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
    headersDoneCB.apply(ctx)
    requestSocketAddressCB.apply(ctx, '0.0.0.0', 5555)

    then:
    bundle.get(KnownAddresses.REQUEST_SCHEME) == 'https'
  }
}
