package com.datadog.appsec.gateway

import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.StringKVPair
import com.datadog.appsec.report.ReportService
import com.datadog.appsec.report.raw.events.attack.Attack010
import datadog.trace.api.Function
import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase
import spock.lang.Specification

class GatewayBridgeSpecification extends Specification {
  SubscriptionService ig = Mock()
  EventDispatcher eventDispatcher = Mock()
  ReportService reportService = Mock()
  AppSecRequestContext ctx = new AppSecRequestContext()

  EventProducerService.DataSubscriberInfo nonEmptyDsInfo = {
    EventProducerService.DataSubscriberInfo i = Mock()
    i.empty >> false
    i
  }()

  GatewayBridge bridge = new GatewayBridge(ig, eventDispatcher, reportService)

  Supplier<Flow<RequestContext>> requestStartedCB
  Function<RequestContext, Flow<Void>> requestEndedCB
  TriConsumer<RequestContext, String, String> headerCB
  Function<RequestContext, Flow<Void>> headersDoneCB
  BiFunction<RequestContext, URIDataAdapter, Flow<Void>> requestURICB
  BiFunction<RequestContext, String, Flow<Void>> requestIpCB

  void setup() {
    callInitAndCaptureCBs()
  }

  void 'request_start produces appsec context and publishes event'() {
    when:
    Flow<RequestContext> startFlow = requestStartedCB.get()

    then:
    1 * eventDispatcher.publishEvent(
      _ as AppSecRequestContext, EventType.REQUEST_START)
    RequestContext producedCtx = startFlow.getResult()
    producedCtx instanceof AppSecRequestContext
    startFlow.action == Flow.Action.Noop.INSTANCE
  }

  void 'request_end closes context reports attacks and publishes event'() {
    Attack010 attack = Mock()
    AppSecRequestContext mockCtx = Mock()

    when:
    def flow = requestEndedCB.apply(mockCtx)

    then:
    1 * mockCtx.transferCollectedAttacks() >> [attack]
    1 * mockCtx.close()
    1 * reportService.reportAttack(attack)
    1 * eventDispatcher.publishEvent(mockCtx, EventType.REQUEST_END)
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
    def headers = ctx.collectedHeaders
    assert headers['header1'] == ['value 1.1', 'value 1.2', 'value 1.3']
    assert headers['header2'] == ['value 2']
  }

  void 'headers are split between cookies and non cookies'() {
    when:
    headerCB.accept(ctx, 'Cookie', 'foo=bar;foo2=bar2')
    headerCB.accept(ctx, 'Cookie', 'foo3=bar3')
    headerCB.accept(ctx, 'Another-Header', 'another value')

    then:
    def collectedHeaders = ctx.collectedHeaders
    assert collectedHeaders['another-header'] == ['another value']
    assert !collectedHeaders.containsKey('cookie')

    def cookies = ctx.collectedCookies
    assert cookies.contains(new StringKVPair('foo', 'bar'))
    assert cookies.contains(new StringKVPair('foo2', 'bar2'))
    assert cookies.contains(new StringKVPair('foo3', 'bar3'))
  }

  void 'headers provided after headers ended are ignored'() {
    DataBundle bundle

    when:
    ctx.rawURI = '/'
    ctx.ip = '0.0.0.0'
    eventDispatcher.getDataSubscribers(_, _) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    headersDoneCB.apply(ctx)
    headerCB.accept(ctx, 'header', 'value')

    then:
    thrown(IllegalStateException)
    assert bundle.get(KnownAddresses.HEADERS_NO_COOKIES).isEmpty()
  }

  void 'the ip provided and distributed'() {
    DataBundle bundle

    when:
    ctx.ip = '0.0.0.0'
    eventDispatcher.getDataSubscribers(_, _) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    headersDoneCB.apply(ctx)
    requestURICB.apply(ctx, TestURIDataAdapter.create('/a'))
    requestIpCB.apply(ctx, '0.0.0.0')

    then:
    bundle.get(KnownAddresses.REQUEST_CLIENT_IP) == '0.0.0.0'
  }

  void 'setting headers then request uri triggers initial data event'() {
    DataBundle bundle

    when:
    ctx.ip = '0.0.0.0'
    eventDispatcher.getDataSubscribers(_, _) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    headersDoneCB.apply(ctx)
    requestURICB.apply(ctx, TestURIDataAdapter.create('/a'))
    requestIpCB.apply(ctx, '0.0.0.0')

    then:
    bundle.get(KnownAddresses.REQUEST_URI_RAW) == '/a'
  }

  void 'the raw request uri is provided and decoded'() {
    DataBundle bundle
    def adapter = TestURIDataAdapter.create(uri, supportsRaw)

    when:
    ctx.ip = '0.0.0.0'
    eventDispatcher.getDataSubscribers(ctx, { KnownAddresses.REQUEST_URI_RAW in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    requestURICB.apply(ctx, adapter)
    headersDoneCB.apply(ctx)
    requestIpCB.apply(ctx, '0.0.0.0')

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
    ctx.ip = '0.0.0.0'
    eventDispatcher.getDataSubscribers(ctx, { KnownAddresses.REQUEST_URI_RAW in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    requestURICB.apply(ctx, adapter)
    headersDoneCB.apply(ctx)
    requestIpCB.apply(ctx, '0.0.0.0')

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
    1 * ig.registerCallback(Events.REQUEST_STARTED, _) >> { requestStartedCB = it[1]; null }
    1 * ig.registerCallback(Events.REQUEST_ENDED, _) >> { requestEndedCB = it[1]; null }
    1 * ig.registerCallback(Events.REQUEST_URI_RAW, _) >> { requestURICB = it[1]; null }
    1 * ig.registerCallback(Events.REQUEST_HEADER, _) >> { headerCB = it[1]; null }
    1 * ig.registerCallback(Events.REQUEST_HEADER_DONE, _) >> { headersDoneCB = it[1]; null }
    1 * ig.registerCallback(Events.REQUEST_CLIENT_IP, _) >> { requestIpCB = it[1]; null }

    bridge.init()
  }

  private static abstract class TestURIDataAdapter extends URIDataAdapterBase {

    static URIDataAdapter create(String uri) {
      create(uri, true)
    }

    static URIDataAdapter create(String uri, boolean supportsRaw) {
      if (supportsRaw) {
        new TestRawAdapter(uri)
      } else {
        new TestNoRawAdapter(uri)
      }
    }

    private final String p
    private final String q

    protected TestURIDataAdapter(String uri) {
      if (null == uri) {
        p = null
        q = null
      } else {
        def parts = uri.split("\\?")
        p = parts[0]
        q = parts.length == 2 ? parts[1] : null
      }
    }

    @Override
    String scheme() {
      return null
    }

    @Override
    String host() {
      return null
    }

    @Override
    int port() {
      return 0
    }

    @Override
    String path() {
      return supportsRaw() ? null : p
    }

    @Override
    String fragment() {
      return null
    }

    @Override
    String query() {
      return supportsRaw() ? null : q
    }

    @Override
    String rawPath() {
      return supportsRaw() ? p : null
    }

    @Override
    boolean hasPlusEncodedSpaces() {
      return false
    }

    @Override
    String rawQuery() {
      return supportsRaw() ? q : null
    }

    private static class TestRawAdapter extends TestURIDataAdapter {
      TestRawAdapter(String uri) {
        super(uri)
      }

      @Override
      boolean supportsRaw() {
        return true
      }
    }

    private static class TestNoRawAdapter extends TestURIDataAdapter {
      TestNoRawAdapter(String uri) {
        super(uri)
      }

      @Override
      boolean supportsRaw() {
        return false
      }
    }
  }
}
