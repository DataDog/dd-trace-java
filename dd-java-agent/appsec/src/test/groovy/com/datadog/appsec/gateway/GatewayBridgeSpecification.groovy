package com.datadog.appsec.gateway


import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.StringKVPair
import datadog.trace.api.Function
import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.SubscriptionService
import spock.lang.Specification

class GatewayBridgeSpecification extends Specification {
  SubscriptionService ig = Mock()
  EventDispatcher eventDispatcher = Mock()
  AppSecRequestContext ctx = new AppSecRequestContext()

  EventProducerService.DataSubscriberInfo nonEmptyDsInfo = {
    EventProducerService.DataSubscriberInfo i = Mock()
    i.empty >> false
    i
  }()

  GatewayBridge bridge = new GatewayBridge(ig, eventDispatcher)

  Supplier<Flow<RequestContext>> requestStartedCB
  Function<? extends RequestContext, Flow<Void>> requestEndedCB
  TriConsumer<? extends RequestContext, String, String> headerCB
  Function<? extends RequestContext, Flow<Void>> headersDoneCB
  BiFunction<? extends RequestContext, String, Flow<Void>> requestURICB

  void setup() {
    callInitAndCaptureCBs()
  }

  void 'request_start produces appsec context and publishes event'() {
    when:
    Flow<RequestContext> startFlow = requestStartedCB.get()

    then:
    1 * eventDispatcher.publishEvent(
      _ as AppSecRequestContext, EventType.REQUEST_START) >> NoopFlow.INSTANCE
    RequestContext producedCtx = startFlow.getResult()
    producedCtx instanceof AppSecRequestContext
    startFlow.action == Flow.Action.Noop.INSTANCE
  }

  void 'request_end closes context and publishes event'() {
    AppSecRequestContext mockCtx = Mock()
    def stubReturnFlow = Mock(Flow)

    when:
    Flow<RequestContext> endFlow = requestEndedCB.apply(mockCtx)

    then:
    1 * mockCtx.close()
    1 * eventDispatcher.publishEvent(mockCtx, EventType.REQUEST_END) >> stubReturnFlow

    endFlow.is(stubReturnFlow)
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
    eventDispatcher.getDataSubscribers(_, _) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    headersDoneCB.apply(ctx)
    headerCB.accept(ctx, 'header', 'value')

    then:
    assert bundle.get(KnownAddresses.HEADERS_NO_COOKIES).isEmpty()
  }

  void 'the raw request uri is provided and decoded'() {
    DataBundle bundle
    String uri = '/foo%6f?foo=bar+1&fo%6f=b%61r+2&xpto'

    when:
    eventDispatcher.getDataSubscribers(ctx, { KnownAddresses.REQUEST_URI_RAW in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    requestURICB.apply(ctx, uri)
    headersDoneCB.apply(ctx)

    then:
    assert bundle.get(KnownAddresses.REQUEST_URI_RAW) == uri

    def query = bundle.get(KnownAddresses.REQUEST_QUERY)
    assert query['foo'] == ['bar 1', 'bar 2']
    assert query['xpto'] == ['']
  }

  void 'exercise all decoding paths'() {
    DataBundle bundle
    String uri = "/?foo=$encoded"

    when:
    eventDispatcher.getDataSubscribers(ctx, { KnownAddresses.REQUEST_URI_RAW in it }) >> nonEmptyDsInfo
    eventDispatcher.publishDataEvent(nonEmptyDsInfo, ctx, _ as DataBundle, false) >>
    { bundle = it[2]; NoopFlow.INSTANCE }

    and:
    requestURICB.apply(ctx, uri)
    headersDoneCB.apply(ctx)

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

    bridge.init()
  }
}
