package com.datadog.appsec.gateway;

import static datadog.trace.api.ProductTraceSource.ASM;
import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.gateway.RequestContextSlot.APPSEC;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS;
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.ASM_KEEP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PROPAGATED_TRACE_SOURCE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.api.security.ApiSecurityDownstreamSampler;
import com.datadog.appsec.api.security.ApiSecuritySamplerImpl;
import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.event.EventProducerService.DataSubscriberInfo;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.appsec.report.AppSecEventWrapper;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.TagMap;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.appsec.HttpClientResponse;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.http.MediaType;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.LoginEvent;
import datadog.trace.api.telemetry.RuleType;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.lambda.LambdaAppSecHandler;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

class GatewayBridgeTest extends DDJavaSpecification {

  private static final WafMetricCollector ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get();

  private static final String USER_ID = "user";

  private SubscriptionService ig;
  private EventDispatcher eventDispatcher;
  private AppSecRequestContext arCtx;
  private TraceSegment traceSegment;
  private RequestContext ctx;
  private DataSubscriberInfo nonEmptyDsInfo;
  private DataSubscriberInfo emptyDsInfo;
  private TraceSegmentPostProcessor pp;
  private ApiSecuritySamplerImpl requestSampler;
  private ApiSecurityDownstreamSampler downstreamSampler;
  private WafMetricCollector wafMetricCollector;
  private GatewayBridge bridge;
  private int registeredCallbacks;

  private Supplier<Flow<Object>> requestStartedCB;
  private BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCB;
  private TriConsumer<RequestContext, String, String> reqHeaderCB;
  private Function<RequestContext, Flow<Void>> reqHeadersDoneCB;
  private TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> requestMethodURICB;
  private BiFunction<RequestContext, Map<String, ?>, Flow<Void>> pathParamsCB;
  private TriFunction<RequestContext, String, Integer, Flow<Void>> requestSocketAddressCB;
  private BiFunction<RequestContext, String, Flow<Void>> requestInferredAddressCB;
  private BiFunction<RequestContext, StoredBodySupplier, Void> requestBodyStartCB;
  private BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestBodyDoneCB;
  private BiFunction<RequestContext, Object, Flow<Void>> requestBodyProcessedCB;
  private BiFunction<RequestContext, Object, Flow<Void>> responseBodyCB;
  private BiFunction<RequestContext, Integer, Flow<Void>> responseStartedCB;
  private TriConsumer<RequestContext, String, String> respHeaderCB;
  private Function<RequestContext, Flow<Void>> respHeadersDoneCB;
  private BiFunction<RequestContext, String, Flow<Void>> grpcServerMethodCB;
  private BiFunction<RequestContext, Object, Flow<Void>> grpcServerRequestMessageCB;
  private BiConsumer<RequestContext, String> databaseConnectionCB;
  private BiFunction<RequestContext, String, Flow<Void>> databaseSqlQueryCB;
  private BiFunction<RequestContext, HttpClientRequest, Flow<Void>> httpClientRequestCB;
  private BiFunction<RequestContext, HttpClientResponse, Flow<Void>> httpClientResponseCB;
  private BiFunction<RequestContext, Long, Flow<Boolean>> httpClientSamplingCB;
  private BiFunction<RequestContext, String, Flow<Void>> fileLoadedCB;
  private BiFunction<RequestContext, String, Flow<Void>> fileWrittenCB;
  private BiFunction<RequestContext, List<String>, Flow<Void>> requestFilesFilenamesCB;
  private BiFunction<RequestContext, List<String>, Flow<Void>> requestFilesContentCB;
  private BiFunction<RequestContext, String, Flow<Void>> requestSessionCB;
  private BiFunction<RequestContext, String[], Flow<Void>> execCmdCB;
  private BiFunction<RequestContext, String, Flow<Void>> shellCmdCB;
  private BiFunction<RequestContext, String, Flow<Void>> userCB;
  private TriFunction<RequestContext, LoginEvent, String, Flow<Void>> loginEventCB;
  private BiConsumer<RequestContext, String> httpRouteCB;

  @BeforeEach
  void setUp() {
    ig = mock(SubscriptionService.class);
    eventDispatcher = mock(EventDispatcher.class);
    arCtx = new AppSecRequestContext();
    traceSegment = mock(TraceSegment.class);
    ctx = new TestRequestContext(arCtx, traceSegment);
    nonEmptyDsInfo = mock(DataSubscriberInfo.class);
    when(nonEmptyDsInfo.isEmpty()).thenReturn(false);
    emptyDsInfo = mock(DataSubscriberInfo.class);
    when(emptyDsInfo.isEmpty()).thenReturn(true);
    pp = mock(TraceSegmentPostProcessor.class);
    requestSampler = mock(ApiSecuritySamplerImpl.class);
    downstreamSampler = mock(ApiSecurityDownstreamSampler.class);
    bridge =
        new GatewayBridge(
            ig, eventDispatcher, () -> requestSampler, downstreamSampler, singletonList(pp));
    wafMetricCollector = mock(WafMetricCollector.class);

    callInitAndCaptureCBs();
    AppSecSystem.setActive(true);
    WafMetricCollector.INSTANCE = wafMetricCollector;
  }

  @AfterEach
  void tearDown() {
    // no callback is registered other than the ones captured at init
    verify(ig, times(registeredCallbacks)).registerCallback(any(), any());
    WafMetricCollector.INSTANCE = ORIGINAL_METRIC_COLLECTOR;
    bridge.stop();
  }

  @Test
  void requestStartProducesAppsecContextAndPublishesEvent() {
    // when
    Flow<Object> startFlow = requestStartedCB.get();

    // then
    Object producedCtx = startFlow.getResult();
    assertInstanceOf(AppSecRequestContext.class, producedCtx);
    assertSame(Flow.Action.Noop.INSTANCE, startFlow.getAction());
  }

  @Test
  void requestStartReturnsNullContextIfAppsecIsDisabled() {
    // setup
    AppSecSystem.setActive(false);
    try {
      // when
      Flow<Object> startFlow = requestStartedCB.get();

      // then
      Object producedCtx = startFlow.getResult();
      assertNull(producedCtx);
      verifyNoMoreInteractionsOnMocks();
    } finally {
      // cleanup
      AppSecSystem.setActive(true);
    }
  }

  @Test
  void requestEndClosesContextReportsAttacksAndPublishesEvent() {
    AppSecEvent event = mock(AppSecEvent.class);
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    when(mockAppSecCtx.getRequestHeaders())
        .thenReturn(singletonMap("accept", singletonList("header_value")));
    Map<String, List<String>> responseHeaders = new HashMap<>();
    responseHeaders.put("some-header", singletonList("123"));
    responseHeaders.put("content-type", singletonList("text/html; charset=UTF-8"));
    when(mockAppSecCtx.getResponseHeaders()).thenReturn(responseHeaders);
    RequestContext mockCtx = mockRequestContext(mockAppSecCtx);
    AgentSpan spanInfo = mock(AgentSpan.class);
    when(spanInfo.getTags()).thenReturn(TagMap.fromMap(singletonMap("http.client_ip", "1.1.1.1")));
    when(mockAppSecCtx.transferCollectedEvents()).thenReturn(singletonList(event));

    // when
    Flow<Void> flow = requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(spanInfo).getTags();
    verify(mockAppSecCtx).transferCollectedEvents();
    verify(mockAppSecCtx).close();
    verify(spanInfo).setMetric("_dd.appsec.enabled", 1);
    verify(spanInfo).setTag("_dd.runtime_family", "jvm");
    verify(spanInfo).setTag("appsec.event", true);
    verify(spanInfo).setTag("actor.ip", "1.1.1.1");
    verify(traceSegment).setDataTop("appsec", new AppSecEventWrapper(singletonList(event)));
    verify(mockAppSecCtx).isWafBlocked();
    verify(mockAppSecCtx).hasWafErrors();
    verify(mockAppSecCtx).getWafTimeouts();
    verify(mockAppSecCtx).isWafRequestBlockFailure();
    verify(mockAppSecCtx).isWafRateLimited();
    verify(mockAppSecCtx).isWafTruncated();
    // call waf request metric
    verify(wafMetricCollector)
        .wafRequest(
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean());
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
  }

  @Test
  void lambdaRequestEndReachesSharedWafTelemetryWithItsFramework() {
    // given
    AgentTracer.TracerAPI originalTracer = AgentTracer.get();
    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    when(callbackProvider.getCallback(EVENTS.requestStarted())).thenReturn(requestStartedCB);
    when(callbackProvider.getCallback(EVENTS.requestEnded())).thenReturn(requestEndedCB);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.getCallbackProvider(APPSEC)).thenReturn(callbackProvider);
    AgentTracer.forceRegister(tracer);
    try {
      byte[] event = "{\"path\":\"/\",\"requestContext\":{\"httpMethod\":\"GET\"}}".getBytes(UTF_8);
      TagContext lambdaContext =
          (TagContext) LambdaAppSecHandler.processRequestStart(new ByteArrayInputStream(event));
      AppSecRequestContext lambdaAppSecContext =
          (AppSecRequestContext) lambdaContext.getRequestContextDataAppSec();
      RequestContext lambdaRequestContext = mockRequestContext(lambdaAppSecContext);
      AgentSpan span = mock(AgentSpan.class);
      when(span.getRequestContext()).thenReturn(lambdaRequestContext);
      when(span.getTags()).thenReturn(lambdaContext.getTags());
      when(requestSampler.preSampleRequest(lambdaAppSecContext, "aws-lambda")).thenReturn(false);

      // when
      LambdaAppSecHandler.processRequestEnd(span);

      // then
      verify(requestSampler).preSampleRequest(lambdaAppSecContext, "aws-lambda");
      verify(span).setMetric("_dd.appsec.enabled", 1);
      verify(span).setTag("_dd.runtime_family", "jvm");
      verify(pp).processTraceSegment(traceSegment, lambdaAppSecContext, emptyList());
      verify(wafMetricCollector).wafRequest(false, false, false, false, false, false, false, false);
    } finally {
      // cleanup
      AgentTracer.forceRegister(originalTracer);
    }
  }

  @Test
  void actorIpCalculatedFromHeaders() {
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    Map<String, List<String>> requestHeaders = new HashMap<>();
    requestHeaders.put("x-real-ip", singletonList("10.0.0.1"));
    requestHeaders.put("forwarded", asList("for=127.0.0.1", "for=\"[::1]\", for=8.8.8.8"));
    when(mockAppSecCtx.getRequestHeaders()).thenReturn(requestHeaders);
    RequestContext mockCtx = mockRequestContext(mockAppSecCtx);
    AgentSpan spanInfo = mock(AgentSpan.class);
    when(mockAppSecCtx.transferCollectedEvents())
        .thenReturn(singletonList(mock(AppSecEvent.class)));
    when(spanInfo.getTags()).thenReturn(TagMap.fromMap(singletonMap("http.client_ip", "8.8.8.8")));

    // when
    requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(mockAppSecCtx).transferCollectedEvents();
    verify(spanInfo).getTags();
    verify(spanInfo).setTag("actor.ip", "8.8.8.8");
  }

  @Test
  void bridgeCanCollectHeaders() {
    // when
    reqHeaderCB.accept(ctx, "header1", "value 1.1");
    reqHeaderCB.accept(ctx, "header1", "value 1.2");
    reqHeaderCB.accept(ctx, "Header1", "value 1.3");
    reqHeaderCB.accept(ctx, "header2", "value 2");
    respHeaderCB.accept(ctx, "header3", "value 3.1");
    respHeaderCB.accept(ctx, "header3", "value 3.2");
    respHeaderCB.accept(ctx, "header3", "value 3.3");
    respHeaderCB.accept(ctx, "header4", "value 4");

    // then
    Map<String, List<String>> reqHeaders = arCtx.getRequestHeaders();
    assertEquals(asList("value 1.1", "value 1.2", "value 1.3"), reqHeaders.get("header1"));
    assertEquals(singletonList("value 2"), reqHeaders.get("header2"));
    Map<String, List<String>> respHeaders = arCtx.getResponseHeaders();
    assertEquals(asList("value 3.1", "value 3.2", "value 3.3"), respHeaders.get("header3"));
    assertEquals(singletonList("value 4"), respHeaders.get("header4"));
  }

  @Test
  void headersAreSplitBetweenCookiesAndNonCookies() {
    // when
    reqHeaderCB.accept(ctx, "Cookie", "foo=bar;foo2=bar2");
    reqHeaderCB.accept(ctx, "Cookie", "foo3=bar3");
    reqHeaderCB.accept(ctx, "Another-Header", "another value");

    // then
    Map<String, List<String>> collectedHeaders = arCtx.getRequestHeaders();
    assertEquals(singletonList("another value"), collectedHeaders.get("another-header"));
    assertFalse(collectedHeaders.containsKey("cookie"));

    Map<String, ? extends Collection<String>> cookies = arCtx.getCookies();
    assertEquals(singletonList("bar"), cookies.get("foo"));
    assertEquals(singletonList("bar2"), cookies.get("foo2"));
    assertEquals(singletonList("bar3"), cookies.get("foo3"));
  }

  @Test
  void headersProvidedAfterHeadersEndedAreIgnored() throws ExpiredSubscriberInfoException {
    // when
    arCtx.setRawURI("/");
    arCtx.setPeerAddress("0.0.0.0");
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // and
    reqHeadersDoneCB.apply(ctx);

    // then
    assertThrows(IllegalStateException.class, () -> reqHeaderCB.accept(ctx, "header", "value"));
    Map<String, List<String>> data = published.bundle.get(KnownAddresses.HEADERS_NO_COOKIES);
    assertTrue(data == null || data.isEmpty());
  }

  @Test
  void theSocketAddressIsDistributed() throws ExpiredSubscriberInfoException {
    // when
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // and
    reqHeadersDoneCB.apply(ctx);
    requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/a"));
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);

    // then
    assertEquals("0.0.0.0", published.bundle.get(KnownAddresses.REQUEST_CLIENT_IP));
    assertEquals(Integer.valueOf(5555), published.bundle.get(KnownAddresses.REQUEST_CLIENT_PORT));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void theInferredIpAddressIsDistributedIfPublishedBeforeTheSocketAddress()
      throws ExpiredSubscriberInfoException {
    // when
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // and
    reqHeadersDoneCB.apply(ctx);
    requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/a"));
    requestInferredAddressCB.apply(ctx, "1.2.3.4");
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);

    // then
    assertEquals("1.2.3.4", published.bundle.get(KnownAddresses.REQUEST_INFERRED_CLIENT_IP));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void settingHeadersThenRequestUriTriggersInitialDataEvent()
      throws ExpiredSubscriberInfoException {
    // when
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // and
    reqHeadersDoneCB.apply(ctx);
    requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/a"));
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);

    // then
    assertEquals("/a", published.bundle.get(KnownAddresses.REQUEST_URI_RAW));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @TableTest({
    "scenario    | uri                                    | supportsRaw | expected                              ",
    "raw uri     | '/foo%6f?foo=bar+1&fo%6f=b%61r+2&xpto' | true        | '/foo%6f?foo=bar+1&fo%6f=b%61r+2&xpto'",
    "decoded uri | '/fooo?foo=bar 1&foo=bar 2&xpto'       | false       | '/fooo?foo=bar%201&foo=bar%202&xpto'  ",
    "no uri      |                                        | false       | ''                                    "
  })
  void theRawRequestUriIsProvidedAndDecoded(String uri, boolean supportsRaw, String expected)
      throws ExpiredSubscriberInfoException {
    URIDataAdapter adapter = TestURIDataAdapter.create(uri, supportsRaw);

    // when
    subscribeTo(KnownAddresses.REQUEST_URI_RAW);
    PublishedData published = capturePublishedData();

    // and
    requestMethodURICB.apply(ctx, "GET", adapter);
    reqHeadersDoneCB.apply(ctx);
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);

    // then
    assertEquals(expected, published.bundle.get(KnownAddresses.REQUEST_URI_RAW));
    assertGatewayContext(published.gatewayContext, false, false);

    if (uri != null) {
      Map<String, List<String>> query = published.bundle.get(KnownAddresses.REQUEST_QUERY);
      assertEquals(asList("bar 1", "bar 2"), query.get("foo"));
      assertEquals(singletonList(""), query.get("xpto"));
    }
  }

  @ParameterizedTest
  @TableTest({
    "scenario                      | encoded  | decoded ",
    // repl. char: not a valid UTF-8 code unit sequence
    "invalid UTF-8 sequence        | '%80'    | '\uFFFD'",
    "truncated escape              | '%8'     | '%8'    ",
    "invalid second hex digit      | '%8G'    | '%8G'   ",
    "invalid first hex digit       | '%G8'    | '%G8'   ",
    "invalid first hex digit again | '%G8'    | '%G8'   ",
    "non-hex second digit          | '%0:'    | '%0:'   ",
    "two-byte UTF-8 sequence       | '%C2%80' | '\u0080'"
  })
  @MethodSource("exerciseAllDecodingPathsArguments")
  void exerciseAllDecodingPaths(String encoded, String decoded)
      throws ExpiredSubscriberInfoException {
    String uri = "/?foo=" + encoded;
    URIDataAdapter adapter = TestURIDataAdapter.create(uri);

    // when
    subscribeTo(KnownAddresses.REQUEST_URI_RAW);
    PublishedData published = capturePublishedData();

    // and
    requestMethodURICB.apply(ctx, "GET", adapter);
    reqHeadersDoneCB.apply(ctx);
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 80);

    // then
    Map<String, List<String>> query = published.bundle.get(KnownAddresses.REQUEST_QUERY);
    assertEquals(singletonList(decoded), query.get("foo"));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  static Stream<Arguments> exerciseAllDecodingPathsArguments() {
    // newlines cannot be expressed in a table cell
    return Stream.of(arguments("%0A", "\n"), arguments("%0a", "\n"));
  }

  @Test
  void pathParamsArePublished() throws ExpiredSubscriberInfoException {
    // when
    subscribeTo(KnownAddresses.REQUEST_PATH_PARAMS);
    PublishedData published = capturePublishedData();

    // and
    pathParamsCB.apply(ctx, singletonMap("a", "b"));

    // then
    assertEquals(singletonMap("a", "b"), published.bundle.get(KnownAddresses.REQUEST_PATH_PARAMS));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void pathParamsIsNotPublishedTwice() throws ExpiredSubscriberInfoException {
    // setup
    pathParamsCB.apply(ctx, singletonMap("a", "b"));
    clearInvocations(eventDispatcher);

    // when
    Flow<Void> flow = pathParamsCB.apply(ctx, singletonMap("c", "d"));

    // then
    assertSame(NoopFlow.INSTANCE, flow);
    verify(eventDispatcher, never()).getDataSubscribers(KnownAddresses.REQUEST_PATH_PARAMS);
    verify(eventDispatcher, never()).publishDataEvent(any(), any(), any(), any());
  }

  @Test
  void forwardsRequestBodyStartEventsAndStoresTheSupplier() {
    StoredBodySupplier supplier = mock(StoredBodySupplier.class);

    // setup
    when(supplier.get()).thenReturn("foobar");

    // expect
    assertNull(arCtx.getStoredRequestBody());

    // when
    requestBodyStartCB.apply(ctx, supplier);

    // then
    assertEquals("foobar", arCtx.getStoredRequestBody());
  }

  @Test
  void forwardsRequestBodyDoneEventsAndDistributesTheBodyContents()
      throws ExpiredSubscriberInfoException {
    StoredBodySupplier supplier = mock(StoredBodySupplier.class);

    // setup
    when(supplier.get()).thenReturn("foobar");
    subscribeTo(KnownAddresses.REQUEST_BODY_RAW);
    PublishedData published = capturePublishedData();

    // when
    requestBodyDoneCB.apply(ctx, supplier);

    // then
    assertEquals("foobar", published.bundle.get(KnownAddresses.REQUEST_BODY_RAW));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void requestBodyDoesNotGetPublishedTwice() {
    StoredBodySupplier supplier = mock(StoredBodySupplier.class);

    // given
    when(supplier.get()).thenReturn("foobar");

    // when
    arCtx.setRawReqBodyPublished(true);
    Flow<Void> flow = requestBodyDoneCB.apply(ctx, supplier);

    // then
    assertSame(NoopFlow.INSTANCE, flow);
    verify(eventDispatcher, never()).getDataSubscribers(KnownAddresses.REQUEST_BODY_RAW);
  }

  @Test
  void forwardRequestBodyProcessed() throws ExpiredSubscriberInfoException {
    Object obj = "hello";

    // setup
    subscribeTo(KnownAddresses.REQUEST_BODY_OBJECT);
    PublishedData published = capturePublishedData();

    // when
    requestBodyProcessedCB.apply(ctx, obj);

    // then
    assertEquals("hello", published.bundle.get(KnownAddresses.REQUEST_BODY_OBJECT));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void processedBodyDoesNotPublishedTwice() throws ExpiredSubscriberInfoException {
    // when
    arCtx.setConvertedReqBodyPublished(true);
    Flow<Void> flow = requestBodyProcessedCB.apply(ctx, new Object());

    // then
    assertSame(NoopFlow.INSTANCE, flow);
    verify(eventDispatcher, never()).getDataSubscribers(KnownAddresses.REQUEST_BODY_OBJECT);
    verify(eventDispatcher, never()).publishDataEvent(any(), any(), any(), any());
  }

  @Test
  void requestBodyTransformsObjectAndPublishes() throws ExpiredSubscriberInfoException {
    // setup
    subscribeTo(KnownAddresses.REQUEST_BODY_OBJECT);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = requestBodyProcessedCB.apply(ctx, new FooBar());

    // then
    verifyPublishedOnce();
    assertEquals(
        singletonMap("foo", "bar"), published.bundle.get(KnownAddresses.REQUEST_BODY_OBJECT));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void forwardsRequestMethod() throws ExpiredSubscriberInfoException {
    URIDataAdapter adapter = TestURIDataAdapter.create("http://example.com/");

    // setup
    subscribeTo(KnownAddresses.REQUEST_METHOD);
    PublishedData published = capturePublishedData();

    // when
    requestMethodURICB.apply(ctx, "POST", adapter);
    reqHeadersDoneCB.apply(ctx);
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);

    // then
    assertEquals("POST", published.bundle.get(KnownAddresses.REQUEST_METHOD));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void schemeIsExtractedFromTheUriAdapter() throws ExpiredSubscriberInfoException {
    URIDataAdapter adapter = TestURIDataAdapter.create("https://example.com/");

    // when
    subscribeTo(KnownAddresses.REQUEST_SCHEME);
    PublishedData published = capturePublishedData();

    // and
    requestMethodURICB.apply(ctx, "GET", adapter);
    reqHeadersDoneCB.apply(ctx);
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);

    // then
    assertEquals("https", published.bundle.get(KnownAddresses.REQUEST_SCHEME));
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void requestDataDoesNotPublishedTwice() throws ExpiredSubscriberInfoException {
    // when
    arCtx.setReqDataPublished(true);
    Flow<Void> socketAddressFlow = requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);
    Flow<Void> headersDoneFlow = reqHeadersDoneCB.apply(ctx);
    Flow<Void> methodUriFlow =
        requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/a"));

    // then
    assertSame(NoopFlow.INSTANCE, socketAddressFlow);
    assertSame(NoopFlow.INSTANCE, headersDoneFlow);
    assertSame(NoopFlow.INSTANCE, methodUriFlow);
    verify(eventDispatcher, never()).getDataSubscribers(KnownAddresses.REQUEST_SCHEME);
    verify(eventDispatcher, never()).publishDataEvent(any(), any(), any(), any());
  }

  @Test
  void requestMethodUriCallbackCalledTwiceWithDifferentUrisDoesNotThrow() {
    // Reproduces: https://github.com/DataDog/dd-trace-java/issues/10700
    // when
    assertDoesNotThrow(() -> {
      requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/a"));
      requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/b"));
    });

    // then
    assertEquals("/a", arCtx.getSavedRawURI());
  }

  @Test
  void responseStartProducesAppsecContextAndPublishesEvent() throws ExpiredSubscriberInfoException {
    subscribeTo(KnownAddresses.RESPONSE_STATUS);
    capturePublishedData();

    // when
    Flow<Void> responseStartedFlow = responseStartedCB.apply(ctx, 404);
    Flow<Void> headersDoneFlow = respHeadersDoneCB.apply(ctx);

    // then
    verifyPublishedOnce();
    assertNull(responseStartedFlow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, responseStartedFlow.getAction());
    assertNull(headersDoneFlow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, headersDoneFlow.getAction());
  }

  @Test
  void grpcServerMessageRecvTransformsObjectAndPublishes() throws ExpiredSubscriberInfoException {
    // setup
    subscribeTo(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = grpcServerRequestMessageCB.apply(ctx, new FooBar());

    // then
    verifyPublishedOnce();
    assertEquals(
        singletonMap("foo", "bar"),
        published.bundle.get(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, false);
  }

  @Test
  void grpcServerMethodPublishes() throws ExpiredSubscriberInfoException {
    // setup
    when(eventDispatcher.getDataSubscribers(KnownAddresses.GRPC_SERVER_METHOD))
        .thenReturn(nonEmptyDsInfo);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = grpcServerMethodCB.apply(ctx, "/my.package.Greeter/SayHello");

    // then
    verifyPublishedOnce();
    assertEquals(
        "/my.package.Greeter/SayHello", published.bundle.get(KnownAddresses.GRPC_SERVER_METHOD));
    assertNotNull(published.gatewayContext);
    assertGatewayContext(published.gatewayContext, true, false);
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
  }

  @Test
  void processDatabaseType() {
    // setup
    subscribeTo(KnownAddresses.DB_TYPE);

    // when
    databaseConnectionCB.accept(ctx, "postgresql");

    // then
    assertEquals("postgresql", arCtx.getDbType());
  }

  @Test
  void processJdbcStatementQueryObject() throws ExpiredSubscriberInfoException {
    // setup
    subscribeTo(KnownAddresses.DB_SQL_QUERY);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = databaseSqlQueryCB.apply(ctx, "SELECT * FROM foo");

    // then
    verifyPublishedOnce();
    assertEquals("SELECT * FROM foo", published.bundle.get(KnownAddresses.DB_SQL_QUERY));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, true);
  }

  @ParameterizedTest(name = "process http client request sampling (sampled={0})")
  @ValueSource(booleans = {true, false})
  void processHttpClientRequestSampling(boolean sampled) {
    // setup
    subscribeTo(KnownAddresses.IO_NET_URL);
    when(downstreamSampler.sampleHttpClientRequest(arCtx, 1L)).thenReturn(sampled);

    // when
    Flow<Boolean> flow = httpClientSamplingCB.apply(ctx, 1L);

    // then
    verify(downstreamSampler).sampleHttpClientRequest(arCtx, 1L);
    assertEquals(sampled, flow.getResult());
  }

  @ParameterizedTest(name = "process http client request (sampled={0})")
  @ValueSource(booleans = {true, false})
  void processHttpClientRequest(boolean sampled) throws ExpiredSubscriberInfoException {
    // setup
    String url = "https://www.datadoghq.com/";
    String method = "POST";
    Map<String, List<String>> headers = singletonMap("X-AppSec-TEst", singletonList("true"));
    MediaType contentType = MediaType.parse("application/json");
    ByteArrayInputStream body = new ByteArrayInputStream("{\"hello\": \"World!\"}".getBytes(UTF_8));
    subscribeTo(KnownAddresses.IO_NET_URL);
    when(downstreamSampler.isSampled(eq(arCtx), anyLong())).thenReturn(sampled);
    PublishedData published = capturePublishedData();

    // when
    HttpClientRequest request = new HttpClientRequest(1L, url, method, headers);
    request.setBody(contentType, body);
    Flow<Void> flow = httpClientRequestCB.apply(ctx, request);

    // then
    verifyPublishedOnce();
    DataBundle bundle = published.bundle;
    assertEquals(sampled ? 4 : 3, bundle.size());
    assertEquals(url, bundle.get(KnownAddresses.IO_NET_URL));
    assertEquals(method, bundle.get(KnownAddresses.IO_NET_REQUEST_METHOD));
    assertEquals(toLowerCaseHeaders(headers), bundle.get(KnownAddresses.IO_NET_REQUEST_HEADERS));
    if (sampled) {
      assertEquals(singletonMap("hello", "World!"), bundle.get(KnownAddresses.IO_NET_REQUEST_BODY));
    }
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, true);
    assertEquals(RuleType.SSRF_REQUEST, published.gatewayContext.raspRuleType);
  }

  @ParameterizedTest(name = "process http client response (sampled={0})")
  @ValueSource(booleans = {true, false})
  void processHttpClientResponse(boolean sampled) throws ExpiredSubscriberInfoException {
    // setup
    int status = 200;
    Map<String, List<String>> headers = singletonMap("X-AppSec-TEst", singletonList("true"));
    MediaType contentType = MediaType.parse("application/json");
    ByteArrayInputStream body = new ByteArrayInputStream("{\"hello\": \"World!\"}".getBytes(UTF_8));
    subscribeTo(KnownAddresses.IO_NET_RESPONSE_STATUS);
    when(downstreamSampler.isSampled(eq(arCtx), anyLong())).thenReturn(sampled);
    PublishedData published = capturePublishedData();

    // when
    HttpClientResponse response = new HttpClientResponse(1L, status, headers);
    response.setBody(contentType, body);
    Flow<Void> flow = httpClientResponseCB.apply(ctx, response);

    // then
    verifyPublishedOnce();
    DataBundle bundle = published.bundle;
    assertEquals(sampled ? 3 : 2, bundle.size());
    assertEquals(Integer.toString(status), bundle.get(KnownAddresses.IO_NET_RESPONSE_STATUS));
    assertEquals(toLowerCaseHeaders(headers), bundle.get(KnownAddresses.IO_NET_RESPONSE_HEADERS));
    if (sampled) {
      assertEquals(
          singletonMap("hello", "World!"), bundle.get(KnownAddresses.IO_NET_RESPONSE_BODY));
    }
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, true);
    assertEquals(RuleType.SSRF_RESPONSE, published.gatewayContext.raspRuleType);
  }

  @Test
  void processFileLoaded() throws ExpiredSubscriberInfoException {
    // setup
    String path = "https://www.datadoghq.com/demo/file.txt";
    subscribeTo(KnownAddresses.IO_FS_FILE);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = fileLoadedCB.apply(ctx, path);

    // then
    verifyPublishedOnce();
    assertEquals(path, published.bundle.get(KnownAddresses.IO_FS_FILE));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, true);
  }

  @Test
  void processFileWritten() throws ExpiredSubscriberInfoException {
    // setup
    String path = "/tmp/output.txt";
    subscribeTo(KnownAddresses.IO_FS_FILE, KnownAddresses.IO_FS_FILE_WRITE);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = fileWrittenCB.apply(ctx, path);

    // then
    verifyPublishedOnce();
    assertEquals(path, published.bundle.get(KnownAddresses.IO_FS_FILE));
    assertEquals(path, published.bundle.get(KnownAddresses.IO_FS_FILE_WRITE));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, true);
  }

  @Test
  void processRequestFilesFilenames() throws ExpiredSubscriberInfoException {
    // setup
    List<String> filenames = asList("malicious.php", "document.pdf");
    subscribeTo(KnownAddresses.REQUEST_FILES_FILENAMES);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = requestFilesFilenamesCB.apply(ctx, filenames);

    // then
    verifyPublishedOnce();
    assertEquals(filenames, published.bundle.get(KnownAddresses.REQUEST_FILES_FILENAMES));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void processRequestFilesFilenamesWithEmptyListReturnsNoop()
      throws ExpiredSubscriberInfoException {
    // when
    Flow<Void> flow = requestFilesFilenamesCB.apply(ctx, emptyList());

    // then
    assertSame(NoopFlow.INSTANCE, flow);
    verify(eventDispatcher, never()).publishDataEvent(any(), any(), any(), any());
  }

  @Test
  void processRequestFilesContent() throws ExpiredSubscriberInfoException {
    // setup
    List<String> filesContent = asList("%PDF-1.4 malicious content", "#!/bin/bash\nrm -rf /");
    subscribeTo(KnownAddresses.REQUEST_FILES_CONTENT);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = requestFilesContentCB.apply(ctx, filesContent);

    // then
    verifyPublishedOnce();
    assertEquals(filesContent, published.bundle.get(KnownAddresses.REQUEST_FILES_CONTENT));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, false, false);
  }

  @Test
  void processRequestFilesContentWithEmptyListReturnsNoop() throws ExpiredSubscriberInfoException {
    // when
    Flow<Void> flow = requestFilesContentCB.apply(ctx, emptyList());

    // then
    assertSame(NoopFlow.INSTANCE, flow);
    verify(eventDispatcher, never()).publishDataEvent(any(), any(), any(), any());
  }

  @Test
  void processExecCmd() throws ExpiredSubscriberInfoException {
    // setup
    String[] cmd = {"/bin/../usr/bin/reboot", "-f"};
    subscribeTo(KnownAddresses.EXEC_CMD);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = execCmdCB.apply(ctx, cmd);

    // then
    verifyPublishedOnce();
    // the address is declared as a String but the bridge publishes the command array
    assertArrayEquals(cmd, (String[]) rawValue(published.bundle, KnownAddresses.EXEC_CMD));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, true);
  }

  @Test
  void processShellCmd() throws ExpiredSubscriberInfoException {
    // setup
    String cmd = "$(cat /etc/passwd 1>&2 ; echo .)";
    subscribeTo(KnownAddresses.SHELL_CMD);
    PublishedData published = capturePublishedData();

    // when
    Flow<Void> flow = shellCmdCB.apply(ctx, cmd);

    // then
    verifyPublishedOnce();
    assertEquals(cmd, published.bundle.get(KnownAddresses.SHELL_CMD));
    assertNull(flow.getResult());
    assertSame(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertGatewayContext(published.gatewayContext, true, true);
  }

  @Test
  void callsTraceSegmentPostProcessor() {
    // setup
    AgentSpan span = stubSpan();

    // when
    requestEndedCB.apply(ctx, span);

    // then
    verify(pp).processTraceSegment(traceSegment, arCtx, emptyList());
  }

  @Test
  void noAppsecEventsIfWasNotCreatedRequestContextInRequestStartEvent() {
    RequestContext emptyCtx = new TestRequestContext(null, traceSegment);
    StoredBodySupplier supplier = mock(StoredBodySupplier.class);
    AgentSpan spanInfo = mock(AgentSpan.class);
    Object obj = "obj";

    // when
    // request start event doesn't happen and not create AppSecRequestContext
    Flow<Void> flowMethod =
        requestMethodURICB.apply(emptyCtx, "GET", TestURIDataAdapter.create("/a"));
    Flow<Void> flowParams = pathParamsCB.apply(emptyCtx, singletonMap("a", "b"));
    Flow<Void> flowSock = requestSocketAddressCB.apply(emptyCtx, "0.0.0.0", 5555);
    reqHeaderCB.accept(emptyCtx, "header_name", "header_value");
    Flow<Void> flowHeadersDone = reqHeadersDoneCB.apply(emptyCtx);
    requestBodyStartCB.apply(emptyCtx, supplier);
    Flow<Void> flowBodyEnd = requestBodyDoneCB.apply(emptyCtx, supplier);
    Flow<Void> flowBodyProc = requestBodyProcessedCB.apply(emptyCtx, obj);
    Flow<Void> flowReqEnd = requestEndedCB.apply(emptyCtx, spanInfo);
    Object appSecReqCtx = emptyCtx.getData(APPSEC);

    // then
    assertNull(appSecReqCtx);
    assertSame(NoopFlow.INSTANCE, flowMethod);
    assertSame(NoopFlow.INSTANCE, flowParams);
    assertSame(NoopFlow.INSTANCE, flowSock);
    assertSame(NoopFlow.INSTANCE, flowHeadersDone);
    assertSame(NoopFlow.INSTANCE, flowBodyEnd);
    assertSame(NoopFlow.INSTANCE, flowBodyProc);
    assertSame(NoopFlow.INSTANCE, flowReqEnd);
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void defaultRequestHeadersAreAlwaysSetWhenAppsecIsEnabled() {
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    Map<String, List<String>> requestHeaders = new HashMap<>();
    requestHeaders.put("host", singletonList("localhost"));
    requestHeaders.put("accept", singletonList("text/plain"));
    requestHeaders.put("content-type", singletonList("application/json"));
    requestHeaders.put("user-agent", singletonList("mozilla"));
    requestHeaders.put(
        "x-amzn-trace-id", singletonList("Root=1-65ae48bc-04fb551979979b6c57973027"));
    requestHeaders.put(
        "cloudfront-viewer-ja3-fingerprint", singletonList("e7d705a3286e19ea42f587b344ee6865"));
    requestHeaders.put("cf-ray", singletonList("230b030023ae2822-SJC"));
    requestHeaders.put(
        "x-cloud-trace-context", singletonList("105445aa7843bc8bf206b12000100000/1"));
    requestHeaders.put("x-appgw-trace-id", singletonList("ac882cd65a2712a0fe1289ec2bb6aee7"));
    requestHeaders.put("x-sigsci-requestid", singletonList("55c24b96ca84c02201000001"));
    requestHeaders.put("x-sigsci-tags", singletonList("SQLI, XSS"));
    requestHeaders.put(
        "akamai-user-risk", singletonList("uuid=913c4545-757b-4d8d-859d-e1361a828361;status=0"));
    when(mockAppSecCtx.getRequestHeaders()).thenReturn(requestHeaders);
    Map<String, List<String>> responseHeaders = new HashMap<>();
    responseHeaders.put("content-type", singletonList("text/plain"));
    responseHeaders.put("content-length", singletonList("13"));
    when(mockAppSecCtx.getResponseHeaders()).thenReturn(responseHeaders);
    RequestContext mockCtx = mockRequestContext(mockAppSecCtx);
    AgentSpan spanInfo = mock(AgentSpan.class);
    when(spanInfo.getTags()).thenReturn(TagMap.fromMap(singletonMap("http.route", "/")));
    when(mockAppSecCtx.transferCollectedEvents()).thenReturn(emptyList());

    // when
    requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(mockAppSecCtx).transferCollectedEvents();
    verify(traceSegment, never()).setTagTop(eq("http.request.headers.host"), any());
    verify(traceSegment).setTagTop("http.request.headers.accept", "text/plain");
    verify(traceSegment).setTagTop("http.request.headers.content-type", "application/json");
    verify(traceSegment).setTagTop("http.request.headers.user-agent", "mozilla");
    verify(traceSegment)
        .setTagTop(
            "http.request.headers.x-amzn-trace-id", "Root=1-65ae48bc-04fb551979979b6c57973027");
    verify(traceSegment)
        .setTagTop(
            "http.request.headers.cloudfront-viewer-ja3-fingerprint",
            "e7d705a3286e19ea42f587b344ee6865");
    verify(traceSegment).setTagTop("http.request.headers.cf-ray", "230b030023ae2822-SJC");
    verify(traceSegment)
        .setTagTop(
            "http.request.headers.x-cloud-trace-context", "105445aa7843bc8bf206b12000100000/1");
    verify(traceSegment)
        .setTagTop("http.request.headers.x-appgw-trace-id", "ac882cd65a2712a0fe1289ec2bb6aee7");
    verify(traceSegment)
        .setTagTop("http.request.headers.x-sigsci-requestid", "55c24b96ca84c02201000001");
    verify(traceSegment).setTagTop("http.request.headers.x-sigsci-tags", "SQLI, XSS");
    verify(traceSegment)
        .setTagTop(
            "http.request.headers.akamai-user-risk",
            "uuid=913c4545-757b-4d8d-859d-e1361a828361;status=0");
    verify(traceSegment).setTagTop("http.response.headers.content-type", "text/plain");
    verify(traceSegment).setTagTop("http.response.headers.content-length", "13");
  }

  @TableTest({
    "scenario      | tag                                     | userTracking",
    "login success | appsec.events.users.login.success.track | true        ",
    "login failure | appsec.events.users.login.failure.track | true        ",
    "unrelated tag | appsec.another.unrelated.tag            | false       "
  })
  void requestHeadersAreAlwaysSetWhenThereAreUserTrackingEvents(String tag, boolean userTracking) {
    // given
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    when(mockAppSecCtx.transferCollectedEvents()).thenReturn(emptyList());
    when(mockAppSecCtx.getRequestHeaders())
        .thenReturn(singletonMap("host", singletonList("localhost")));
    when(mockAppSecCtx.getResponseHeaders())
        .thenReturn(singletonMap("content-type", singletonList("text/plain")));
    RequestContext mockCtx = mockRequestContext(mockAppSecCtx);
    AgentSpan spanInfo = stubSpan();
    when(traceSegment.getTagTop(tag)).thenReturn(true);

    // when
    requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(traceSegment, times(userTracking ? 1 : 0))
        .setTagTop("http.request.headers.host", "localhost");
    verify(traceSegment).setTagTop("http.response.headers.content-type", "text/plain");
  }

  @Test
  void fingerprintsAreSetInTheSpanAfterARequest() {
    // given
    AppSecRequestContext appSecCtx = new AppSecRequestContext();
    appSecCtx.reportDerivatives(
        singletonMap("_dd.appsec.fp.http.endpoint", singletonMap("value", "xyz")));
    RequestContext mockCtx = mockRequestContext(appSecCtx);
    AgentSpan spanInfo = stubSpan();

    // when
    requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(traceSegment).setTagTop("_dd.appsec.fp.http.endpoint", "xyz");
  }

  @Test
  void processSessionIds() throws ExpiredSubscriberInfoException {
    // setup
    subscribeToAll();
    PublishedData published = capturePublishedData();
    String sessionId = UUID.randomUUID().toString();

    // when
    requestSessionCB.apply(ctx, sessionId);

    // then
    verifyPublishedOnce();
    assertEquals(sessionId, published.bundle.get(KnownAddresses.SESSION_ID));
    assertFalse(published.gatewayContext.isTransient);
  }

  @Test
  void testOnUserEvent() throws ExpiredSubscriberInfoException {
    // setup
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // when
    userCB.apply(ctx, USER_ID);

    // then
    verifyPublishedOnce();
    assertEquals(USER_ID, published.bundle.get(KnownAddresses.USER_ID));
    assertFalse(published.gatewayContext.isTransient);

    // when
    userCB.apply(ctx, USER_ID);

    // then: no call to the WAF for duplicated calls
    verify(eventDispatcher).publishDataEvent(any(), any(), any(), any());
  }

  @Test
  void testOnSignup() throws ExpiredSubscriberInfoException {
    // setup
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // when
    loginEventCB.apply(ctx, SIGN_UP, USER_ID);

    // then
    verifyPublishedOnce();
    assertEquals(USER_ID, published.bundle.get(KnownAddresses.USER_LOGIN));
    assertNotNull(published.bundle.get(KnownAddresses.SIGN_UP));
    assertFalse(published.gatewayContext.isTransient);
  }

  @Test
  void testOnLoginSuccess() throws ExpiredSubscriberInfoException {
    // setup
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // when
    loginEventCB.apply(ctx, LOGIN_SUCCESS, USER_ID);

    // then
    verifyPublishedOnce();
    assertEquals(USER_ID, published.bundle.get(KnownAddresses.USER_LOGIN));
    assertNotNull(published.bundle.get(KnownAddresses.LOGIN_SUCCESS));
    assertFalse(published.gatewayContext.isTransient);
  }

  @Test
  void testOnLoginFailure() throws ExpiredSubscriberInfoException {
    // setup
    subscribeToAll();
    PublishedData published = capturePublishedData();

    // when
    loginEventCB.apply(ctx, LOGIN_FAILURE, USER_ID);

    // then
    verifyPublishedOnce();
    assertEquals(USER_ID, published.bundle.get(KnownAddresses.USER_LOGIN));
    assertNotNull(published.bundle.get(KnownAddresses.LOGIN_FAILURE));
    assertFalse(published.gatewayContext.isTransient);
  }

  @Test
  void testConfigurationUpdatesShouldResetCachedSubscriptions()
      throws ExpiredSubscriberInfoException {
    when(eventDispatcher.getDataSubscribers(KnownAddresses.SESSION_ID))
        .thenReturn(emptyDsInfo, nonEmptyDsInfo);

    // when
    requestSessionCB.apply(ctx, UUID.randomUUID().toString());

    // then
    verify(eventDispatcher).getDataSubscribers(KnownAddresses.SESSION_ID);
    verify(eventDispatcher, never()).publishDataEvent(any(), any(), any(), any());

    // when
    requestSessionCB.apply(ctx, UUID.randomUUID().toString());

    // then
    verify(eventDispatcher).getDataSubscribers(any());
    verify(eventDispatcher, never()).publishDataEvent(any(), any(), any(), any());

    // when
    bridge.reset();
    requestSessionCB.apply(ctx, UUID.randomUUID().toString());

    // then
    verify(eventDispatcher, times(2)).getDataSubscribers(KnownAddresses.SESSION_ID);
    verify(eventDispatcher).publishDataEvent(any(), any(), any(), any());
  }

  @Test
  void testApiSecuritySampling() {
    // given
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    RequestContext mockCtx = mockRequestContext(mockAppSecCtx);
    AgentSpan spanInfo = mockSpringWebSpan();
    when(mockAppSecCtx.transferCollectedEvents()).thenReturn(emptyList());
    when(requestSampler.preSampleRequest(any(), eq("spring-web"))).thenReturn(true);

    // when
    requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(mockAppSecCtx).transferCollectedEvents();
    verify(spanInfo).getTags();
    verify(requestSampler).preSampleRequest(any(), eq("spring-web"));
    verify(traceSegment, never()).setTagTop(ASM_KEEP, true);
    verify(traceSegment, never()).setTagTop(PROPAGATED_TRACE_SOURCE, ASM);
  }

  @Test
  void testApiSecuritySamplingTraceExcluded() {
    // given
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    RequestContext mockCtx = mockRequestContext(mockAppSecCtx);
    AgentSpan spanInfo = mockSpringWebSpan();
    when(mockAppSecCtx.transferCollectedEvents()).thenReturn(emptyList());
    when(requestSampler.preSampleRequest(any(), eq("spring-web"))).thenReturn(false);

    // when
    requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(mockAppSecCtx).transferCollectedEvents();
    verify(spanInfo).getTags();
    verify(requestSampler).preSampleRequest(any(), eq("spring-web"));
    verify(traceSegment, never()).setTagTop(ASM_KEEP, true);
    verify(traceSegment, never()).setTagTop(PROPAGATED_TRACE_SOURCE, ASM);
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void testApiSecuritySamplingWithTracingDisabled() {
    // given
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    RequestContext mockCtx = mockRequestContext(mockAppSecCtx);
    AgentSpan spanInfo = mockSpringWebSpan();
    when(mockAppSecCtx.transferCollectedEvents()).thenReturn(emptyList());
    when(requestSampler.preSampleRequest(any(), eq("spring-web"))).thenReturn(true);

    // when
    requestEndedCB.apply(mockCtx, spanInfo);

    // then
    verify(mockAppSecCtx).transferCollectedEvents();
    verify(spanInfo).getTags();
    verify(requestSampler).preSampleRequest(any(), eq("spring-web"));
    verify(traceSegment).setTagTop(ASM_KEEP, true);
    verify(traceSegment).setTagTop(PROPAGATED_TRACE_SOURCE, ASM);
  }

  @Test
  void testDefaultWriteRequestHeaders() {
    // given
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);

    // when
    GatewayBridge.writeRequestHeaders(
        mockAppSecCtx, traceSegment, allowedHeaders(), sampleHeaders(), false);

    // then
    verify(traceSegment).setTagTop("http.request.headers.x-allowed-header", "value1");
    verify(traceSegment)
        .setTagTop("http.request.headers.x-multiple-allowed-header", "value1A,value1B");
    verify(traceSegment, times(2)).setTagTop(anyString(), any());
  }

  @Test
  void testDefaultWriteResponseHeaders() {
    // given
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);

    // when
    GatewayBridge.writeResponseHeaders(
        mockAppSecCtx, traceSegment, allowedHeaders(), sampleHeaders(), false);

    // then
    verify(traceSegment).setTagTop("http.response.headers.x-allowed-header", "value1");
    verify(traceSegment)
        .setTagTop("http.response.headers.x-multiple-allowed-header", "value1A,value1B");
    verify(traceSegment, times(2)).setTagTop(anyString(), any());
  }

  @Test
  void testWriteRequestHeadersCollectingAllHeaders() {
    // setup
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    when(mockAppSecCtx.isExtendedDataCollection()).thenReturn(true);
    when(mockAppSecCtx.getExtendedDataCollectionMaxHeaders()).thenReturn(4);
    when(mockAppSecCtx.getCookies()).thenReturn(new HashMap<>());

    // when
    GatewayBridge.writeRequestHeaders(
        mockAppSecCtx, traceSegment, allowedHeaders(), sampleHeaders(), true);

    // then
    verify(traceSegment).setTagTop("http.request.headers.x-allowed-header", "value1");
    verify(traceSegment)
        .setTagTop("http.request.headers.x-multiple-allowed-header", "value1A,value1B");
    verify(traceSegment).setTagTop("http.request.headers.x-other-header-1", "value2");
    verify(traceSegment).setTagTop("http.request.headers.x-other-header-2", "value3");
    verify(traceSegment).setTagTop("_dd.appsec.request.header_collection.discarded", 1);
    verify(traceSegment, times(5)).setTagTop(anyString(), any());
  }

  @Test
  void testWriteResponseHeadersCollectingAllHeaders() {
    // setup
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    when(mockAppSecCtx.isExtendedDataCollection()).thenReturn(true);
    when(mockAppSecCtx.getExtendedDataCollectionMaxHeaders()).thenReturn(4);

    // when
    GatewayBridge.writeResponseHeaders(
        mockAppSecCtx, traceSegment, allowedHeaders(), sampleHeaders(), true);

    // then
    verify(traceSegment).setTagTop("http.response.headers.x-allowed-header", "value1");
    verify(traceSegment)
        .setTagTop("http.response.headers.x-multiple-allowed-header", "value1A,value1B");
    verify(traceSegment).setTagTop("http.response.headers.x-other-header-1", "value2");
    verify(traceSegment).setTagTop("http.response.headers.x-other-header-2", "value3");
    verify(traceSegment).setTagTop("_dd.appsec.response.header_collection.discarded", 1);
    verify(traceSegment, times(5)).setTagTop(anyString(), any());
  }

  @Test
  void testWriteRequestHeadersRedactsAuthorizationHeadersValues() {
    // setup
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    when(mockAppSecCtx.isExtendedDataCollection()).thenReturn(true);
    when(mockAppSecCtx.getExtendedDataCollectionMaxHeaders()).thenReturn(10);
    when(mockAppSecCtx.getCookies()).thenReturn(new HashMap<>());

    Set<String> allowedHeaders = new HashSet<>(asList("content-type", "user-agent"));
    Map<String, List<String>> headers = new HashMap<>();
    headers.put("authorization", singletonList("Bearer abc123xyz"));
    headers.put("proxy-authorization", singletonList("Basic dXNlcjpwYXNz"));
    headers.put("authentication-info", singletonList("nextnonce=\"abc123\""));
    headers.put("proxy-authentication-info", singletonList("nextnonce=\"xyz789\""));
    headers.put("content-type", singletonList("application/json"));
    headers.put("user-agent", singletonList("Mozilla/5.0"));
    headers.put("accept", singletonList("text/html,application/xhtml+xml"));

    // when
    GatewayBridge.writeRequestHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, true);

    // then
    verify(traceSegment).setTagTop("http.request.headers.authorization", "<redacted>");
    verify(traceSegment).setTagTop("http.request.headers.proxy-authorization", "<redacted>");
    verify(traceSegment).setTagTop("http.request.headers.authentication-info", "<redacted>");
    verify(traceSegment).setTagTop("http.request.headers.proxy-authentication-info", "<redacted>");
    verify(traceSegment).setTagTop("http.request.headers.content-type", "application/json");
    verify(traceSegment).setTagTop("http.request.headers.user-agent", "Mozilla/5.0");
    verify(traceSegment)
        .setTagTop("http.request.headers.accept", "text/html,application/xhtml+xml");
    verify(traceSegment, times(7)).setTagTop(anyString(), any());
  }

  @Test
  void testWriteResponseHeadersRedactsAuthorizationHeadersValues() {
    // setup
    AppSecRequestContext mockAppSecCtx = mock(AppSecRequestContext.class);
    when(mockAppSecCtx.isExtendedDataCollection()).thenReturn(true);
    when(mockAppSecCtx.getExtendedDataCollectionMaxHeaders()).thenReturn(10);
    when(mockAppSecCtx.getCookies()).thenReturn(new HashMap<>());

    Set<String> allowedHeaders = new HashSet<>(asList("content-type", "server"));
    Map<String, List<String>> headers = new HashMap<>();
    headers.put("www-authenticate", singletonList("Basic realm=\"example\""));
    headers.put("proxy-authenticate", singletonList("Digest realm=\"example\""));
    headers.put("set-cookie", singletonList("session=abc123; Path=/; HttpOnly"));
    headers.put("content-type", singletonList("text/html; charset=UTF-8"));
    headers.put("server", singletonList("nginx/1.18.0"));

    // when
    GatewayBridge.writeResponseHeaders(mockAppSecCtx, traceSegment, allowedHeaders, headers, true);

    // then
    verify(traceSegment).setTagTop("http.response.headers.www-authenticate", "<redacted>");
    verify(traceSegment).setTagTop("http.response.headers.proxy-authenticate", "<redacted>");
    verify(traceSegment).setTagTop("http.response.headers.set-cookie", "<redacted>");
    verify(traceSegment)
        .setTagTop("http.response.headers.content-type", "text/html; charset=UTF-8");
    verify(traceSegment).setTagTop("http.response.headers.server", "nginx/1.18.0");
    verify(traceSegment, times(5)).setTagTop(anyString(), any());
  }

  @Test
  void testOnHttpRoute() {
    // given
    String route = "dummy-route";

    // when
    httpRouteCB.accept(ctx, route);

    // then
    assertEquals(route, arCtx.getRoute());
  }

  @Test
  void testOnResponseBodyCallback() throws ExpiredSubscriberInfoException {
    when(eventDispatcher.getDataSubscribers(KnownAddresses.RESPONSE_BODY_OBJECT))
        .thenReturn(nonEmptyDsInfo);
    PublishedData published = new PublishedData();
    when(eventDispatcher.publishDataEvent(any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          published.bundle = invocation.getArgument(2);
          return null;
        });

    // when
    responseBodyCB.apply(ctx, singletonMap("test", "this is a test"));

    // then
    verify(eventDispatcher).getDataSubscribers(KnownAddresses.RESPONSE_BODY_OBJECT);
    verify(eventDispatcher).publishDataEvent(any(), any(), any(), any());
    Map<?, ?> body = (Map<?, ?>) published.bundle.get(KnownAddresses.RESPONSE_BODY_OBJECT);
    assertEquals("this is a test", body.get("test"));
  }

  @Test
  void blockingResponseContentTypeSpanTagIsWrittenForAutoBctResolvedToApplicationJson()
      throws ExpiredSubscriberInfoException {
    // setup
    AgentSpan spanInfo = stubSpan();

    // when
    requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/"));
    reqHeaderCB.accept(ctx, "accept", "application/json");
    reqHeadersDoneCB.apply(ctx);
    subscribeToAll();
    capturePublishedData(autoBlockingFlow());
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);
    requestEndedCB.apply(ctx, spanInfo);

    // then
    verify(traceSegment).setTagTop("http.response.headers.content-type", "application/json");
    verify(traceSegment)
        .setTagTop(
            "http.response.headers.content-length",
            String.valueOf(
                BlockingActionHelper.getTemplate(BlockingActionHelper.TemplateType.JSON, null)
                    .length));
  }

  @Test
  void blockingResponseContentTypeSpanTagIsWrittenForAutoBctResolvedToTextHtml()
      throws ExpiredSubscriberInfoException {
    // setup
    AgentSpan spanInfo = stubSpan();

    // when
    requestMethodURICB.apply(ctx, "GET", TestURIDataAdapter.create("/"));
    reqHeaderCB.accept(ctx, "accept", "text/html,application/xhtml+xml");
    reqHeadersDoneCB.apply(ctx);
    subscribeToAll();
    capturePublishedData(autoBlockingFlow());
    requestSocketAddressCB.apply(ctx, "0.0.0.0", 5555);
    requestEndedCB.apply(ctx, spanInfo);

    // then
    verify(traceSegment).setTagTop("http.response.headers.content-type", "text/html;charset=utf-8");
    verify(traceSegment)
        .setTagTop(
            "http.response.headers.content-length",
            String.valueOf(
                BlockingActionHelper.getTemplate(BlockingActionHelper.TemplateType.HTML, null)
                    .length));
  }

  private void callInitAndCaptureCBs() {
    // force all callbacks to be registered
    when(eventDispatcher.allSubscribedDataAddresses())
        .thenReturn(
            Arrays.<Address<?>>asList(
                KnownAddresses.REQUEST_PATH_PARAMS,
                KnownAddresses.REQUEST_BODY_OBJECT,
                KnownAddresses.REQUEST_FILES_FILENAMES,
                KnownAddresses.REQUEST_FILES_CONTENT));

    bridge.init();

    requestStartedCB = registeredCallback(EVENTS.requestStarted());
    requestEndedCB = registeredCallback(EVENTS.requestEnded());
    requestMethodURICB = registeredCallback(EVENTS.requestMethodUriRaw());
    pathParamsCB = registeredCallback(EVENTS.requestPathParams());
    reqHeaderCB = registeredCallback(EVENTS.requestHeader());
    reqHeadersDoneCB = registeredCallback(EVENTS.requestHeaderDone());
    requestSocketAddressCB = registeredCallback(EVENTS.requestClientSocketAddress());
    requestInferredAddressCB = registeredCallback(EVENTS.requestInferredClientAddress());
    requestBodyStartCB = registeredCallback(EVENTS.requestBodyStart());
    requestBodyDoneCB = registeredCallback(EVENTS.requestBodyDone());
    requestBodyProcessedCB = registeredCallback(EVENTS.requestBodyProcessed());
    responseBodyCB = registeredCallback(EVENTS.responseBody());
    responseStartedCB = registeredCallback(EVENTS.responseStarted());
    respHeaderCB = registeredCallback(EVENTS.responseHeader());
    respHeadersDoneCB = registeredCallback(EVENTS.responseHeaderDone());
    grpcServerMethodCB = registeredCallback(EVENTS.grpcServerMethod());
    grpcServerRequestMessageCB = registeredCallback(EVENTS.grpcServerRequestMessage());
    registeredCallback(EVENTS.graphqlServerRequestMessage());
    databaseConnectionCB = registeredCallback(EVENTS.databaseConnection());
    databaseSqlQueryCB = registeredCallback(EVENTS.databaseSqlQuery());
    httpClientRequestCB = registeredCallback(EVENTS.httpClientRequest());
    httpClientResponseCB = registeredCallback(EVENTS.httpClientResponse());
    httpClientSamplingCB = registeredCallback(EVENTS.httpClientSampling());
    fileLoadedCB = registeredCallback(EVENTS.fileLoaded());
    fileWrittenCB = registeredCallback(EVENTS.fileWritten());
    requestSessionCB = registeredCallback(EVENTS.requestSession());
    execCmdCB = registeredCallback(EVENTS.execCmd());
    shellCmdCB = registeredCallback(EVENTS.shellCmd());
    userCB = registeredCallback(EVENTS.user());
    loginEventCB = registeredCallback(EVENTS.loginEvent());
    httpRouteCB = registeredCallback(EVENTS.httpRoute());
    requestFilesFilenamesCB = registeredCallback(EVENTS.requestFilesFilenames());
    requestFilesContentCB = registeredCallback(EVENTS.requestFilesContent());
    verify(ig, times(registeredCallbacks)).registerCallback(any(), any());

    clearInvocations(eventDispatcher);
  }

  /** Verifies the callback for the event type was registered exactly once and returns it. */
  @SuppressWarnings("unchecked")
  private <C> C registeredCallback(EventType<C> eventType) {
    ArgumentCaptor<C> captor = ArgumentCaptor.forClass((Class<C>) (Class<?>) Object.class);
    verify(ig).registerCallback(eq(eventType), captor.capture());
    registeredCallbacks++;
    return captor.getValue();
  }

  private void verifyNoMoreInteractionsOnMocks() {
    verifyNoMoreInteractions(
        ig,
        eventDispatcher,
        traceSegment,
        pp,
        requestSampler,
        downstreamSampler,
        wafMetricCollector);
  }

  /** Returns subscribers only when all the given addresses are requested together. */
  private void subscribeTo(Address<?>... requiredAddresses) {
    when(eventDispatcher.getDataSubscribers(any()))
        .thenAnswer(invocation ->
            asList(invocation.getArguments()).containsAll(asList(requiredAddresses))
                ? nonEmptyDsInfo
                : null);
  }

  private void subscribeToAll() {
    when(eventDispatcher.getDataSubscribers(any())).thenReturn(nonEmptyDsInfo);
  }

  private PublishedData capturePublishedData() throws ExpiredSubscriberInfoException {
    return capturePublishedData(NoopFlow.INSTANCE);
  }

  /** Records the last data published to the WAF and replies with the given flow. */
  private PublishedData capturePublishedData(Flow<Void> result)
      throws ExpiredSubscriberInfoException {
    PublishedData published = new PublishedData();
    when(eventDispatcher.publishDataEvent(
            eq(nonEmptyDsInfo), eq(arCtx), any(DataBundle.class), any(GatewayContext.class)))
        .thenAnswer(invocation -> {
          published.bundle = invocation.getArgument(2);
          published.gatewayContext = invocation.getArgument(3);
          return result;
        });
    return published;
  }

  private void verifyPublishedOnce() throws ExpiredSubscriberInfoException {
    verify(eventDispatcher)
        .publishDataEvent(
            eq(nonEmptyDsInfo), eq(arCtx), any(DataBundle.class), any(GatewayContext.class));
  }

  private RequestContext mockRequestContext(AppSecRequestContext appSecCtx) {
    RequestContext requestContext = mock(RequestContext.class);
    when(requestContext.getData(APPSEC)).thenReturn(appSecCtx);
    when(requestContext.getTraceSegment()).thenReturn(traceSegment);
    return requestContext;
  }

  /** Span with an empty tag map, as returned by a Spock {@code Stub()}. */
  private static AgentSpan stubSpan() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTags()).thenReturn(TagMap.fromMap(emptyMap()));
    return span;
  }

  private static AgentSpan mockSpringWebSpan() {
    AgentSpan span = mock(AgentSpan.class);
    Map<String, Object> tags = new HashMap<>();
    tags.put("http.route", "route");
    tags.put("component", "spring-web");
    when(span.getTags()).thenReturn(TagMap.fromMap(tags));
    return span;
  }

  private static Flow<Void> autoBlockingFlow() {
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);
    return new Flow<Void>() {
      @Override
      public Flow.Action getAction() {
        return rba;
      }

      @Override
      public Void getResult() {
        return null;
      }
    };
  }

  private static void assertGatewayContext(
      GatewayContext gatewayContext, boolean isTransient, boolean isRasp) {
    assertEquals(isTransient, gatewayContext.isTransient);
    assertEquals(isRasp, gatewayContext.isRasp);
  }

  @SuppressWarnings("unchecked")
  private static Object rawValue(DataBundle bundle, Address<?> address) {
    return bundle.get((Address<Object>) address);
  }

  private static Set<String> allowedHeaders() {
    return new HashSet<>(
        asList("x-allowed-header", "x-multiple-allowed-header", "x-always-included"));
  }

  /** Insertion ordered: the collect-all tests rely on which headers exceed the limit. */
  private static Map<String, List<String>> sampleHeaders() {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("x-allowed-header", singletonList("value1"));
    headers.put("x-multiple-allowed-header", asList("value1A", "value1B"));
    headers.put("x-other-header-1", singletonList("value2"));
    headers.put("x-other-header-2", singletonList("value3"));
    headers.put("x-other-header-3", singletonList("value4"));
    return headers;
  }

  private static Map<String, List<String>> toLowerCaseHeaders(Map<String, List<String>> headers) {
    Map<String, List<String>> result = new HashMap<>();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
    }
    return result;
  }

  private static final class PublishedData {
    DataBundle bundle;
    GatewayContext gatewayContext;
  }

  private static final class FooBar {
    @SuppressWarnings("unused")
    private final String foo = "bar";
  }

  private static final class TestRequestContext implements RequestContext {
    private final Object data;
    private final TraceSegment traceSegment;
    private BlockResponseFunction blockResponseFunction;

    TestRequestContext(Object data, TraceSegment traceSegment) {
      this.data = data;
      this.traceSegment = traceSegment;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getData(RequestContextSlot slot) {
      return slot == APPSEC ? (T) data : null;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return traceSegment;
    }

    @Override
    public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {
      this.blockResponseFunction = blockResponseFunction;
    }

    @Override
    public BlockResponseFunction getBlockResponseFunction() {
      return blockResponseFunction;
    }

    @Override
    public <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
      return null;
    }

    @Override
    public void setClientIpAddressData(ClientIpAddressData clientIpAddressData) {}

    @Override
    public ClientIpAddressData getClientIpAddressData() {
      return null;
    }

    @Override
    public void close() {}
  }

  private abstract static class TestURIDataAdapter extends URIDataAdapterBase {
    private static final Pattern SCHEME = Pattern.compile("\\A.+(?=://)");
    private static final Pattern HOST = Pattern.compile("(?<=://).+(?=:|/)");
    private static final Pattern PORT = Pattern.compile("(?<=:)\\d+(?=/|\\z)");

    private final String path;
    private final String query;
    private final String scheme;
    private final String host;
    private final int port;

    static URIDataAdapter create(String uri) {
      return create(uri, true);
    }

    // supportsRaw() is called by the base constructor, so it has to be constant per subclass
    static URIDataAdapter create(String uri, boolean supportsRaw) {
      return supportsRaw ? new TestRawAdapter(uri) : new TestNoRawAdapter(uri);
    }

    protected TestURIDataAdapter(String uri) {
      if (uri == null) {
        path = null;
        query = null;
        scheme = null;
        host = null;
        port = 0;
      } else {
        String[] parts = uri.split("\\?");
        path = parts[0];
        query = parts.length == 2 ? parts[1] : null;
        scheme = firstMatch(SCHEME, uri);
        host = firstMatch(HOST, uri);
        String portMatch = firstMatch(PORT, uri);
        port = portMatch != null ? Integer.parseInt(portMatch) : ("http".equals(scheme) ? 80 : 443);
      }
    }

    private static String firstMatch(Pattern pattern, String input) {
      Matcher matcher = pattern.matcher(input);
      return matcher.find() ? matcher.group() : null;
    }

    @Override
    public String scheme() {
      return scheme;
    }

    @Override
    public String host() {
      return host;
    }

    @Override
    public int port() {
      return port;
    }

    @Override
    public String path() {
      return supportsRaw() ? null : path;
    }

    @Override
    public String fragment() {
      return null;
    }

    @Override
    public String query() {
      return supportsRaw() ? null : query;
    }

    @Override
    public String rawPath() {
      return supportsRaw() ? path : null;
    }

    @Override
    public boolean hasPlusEncodedSpaces() {
      return false;
    }

    @Override
    public String rawQuery() {
      return supportsRaw() ? query : null;
    }

    private static final class TestRawAdapter extends TestURIDataAdapter {
      TestRawAdapter(String uri) {
        super(uri);
      }

      @Override
      public boolean supportsRaw() {
        return true;
      }
    }

    private static final class TestNoRawAdapter extends TestURIDataAdapter {
      TestNoRawAdapter(String uri) {
        super(uri);
      }

      @Override
      public boolean supportsRaw() {
        return false;
      }
    }
  }
}
