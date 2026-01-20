package com.datadog.appsec.gateway;

import static com.datadog.appsec.event.data.MapDataBundle.Builder.CAPACITY_0_2;
import static com.datadog.appsec.event.data.MapDataBundle.Builder.CAPACITY_3_4;
import static com.datadog.appsec.event.data.MapDataBundle.Builder.CAPACITY_6_10;
import static com.datadog.appsec.gateway.AppSecRequestContext.AUTHORIZATION_HEADERS;
import static com.datadog.appsec.gateway.AppSecRequestContext.DEFAULT_REQUEST_HEADERS_ALLOW_LIST;
import static com.datadog.appsec.gateway.AppSecRequestContext.REQUEST_HEADERS_ALLOW_LIST;
import static com.datadog.appsec.gateway.AppSecRequestContext.RESPONSE_HEADERS_ALLOW_LIST;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.api.security.ApiSecurityDownstreamSampler;
import com.datadog.appsec.api.security.ApiSecurityDownstreamSamplerImpl;
import com.datadog.appsec.api.security.ApiSecuritySampler;
import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventProducerService.DataSubscriberInfo;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.event.data.ObjectIntrospection;
import com.datadog.appsec.event.data.SingletonDataBundle;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.appsec.report.AppSecEventWrapper;
import com.datadog.appsec.util.BodyParser;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.appsec.HttpClientPayload;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.appsec.HttpClientResponse;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.LoginEvent;
import datadog.trace.api.telemetry.RuleType;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges the instrumentation gateway and the reactive engine. */
public class GatewayBridge {
  private static final Events<AppSecRequestContext> EVENTS = Events.get();

  private static final Logger log = LoggerFactory.getLogger(GatewayBridge.class);

  private static final Pattern QUERY_PARAM_VALUE_SPLITTER = Pattern.compile("=");
  private static final Pattern QUERY_PARAM_SPLITTER = Pattern.compile("&");
  private static final Map<String, List<String>> EMPTY_QUERY_PARAMS = Collections.emptyMap();

  /** User tracking tags that will force the collection of request headers */
  private static final String[] USER_TRACKING_TAGS = {
    "appsec.events.users.login.success.track",
    "appsec.events.users.login.failure.track",
    "appsec.events.users.signup.track"
  };

  private static final String USER_COLLECTION_MODE_TAG = "_dd.appsec.user.collection_mode";

  private static final Map<LoginEvent, Address<?>> EVENT_MAPPINGS = new EnumMap<>(LoginEvent.class);
  private static final String METASTRUCT_REQUEST_BODY = "http.request.body";

  static {
    EVENT_MAPPINGS.put(LoginEvent.LOGIN_SUCCESS, KnownAddresses.LOGIN_SUCCESS);
    EVENT_MAPPINGS.put(LoginEvent.LOGIN_FAILURE, KnownAddresses.LOGIN_FAILURE);
    EVENT_MAPPINGS.put(LoginEvent.SIGN_UP, KnownAddresses.SIGN_UP);
  }

  private static final String METASTRUCT_EXPLOIT = "exploit";

  private final SubscriptionService subscriptionService;
  private final EventProducerService producerService;
  private final Supplier<ApiSecuritySampler> requestSamplerSupplier;
  private final List<TraceSegmentPostProcessor> traceSegmentPostProcessors;
  private volatile ApiSecurityDownstreamSampler downstreamSampler;

  // subscriber cache
  private volatile DataSubscriberInfo initialReqDataSubInfo;
  private volatile DataSubscriberInfo rawRequestBodySubInfo;
  private volatile DataSubscriberInfo requestBodySubInfo;
  private volatile DataSubscriberInfo responseBodySubInfo;
  private volatile DataSubscriberInfo pathParamsSubInfo;
  private volatile DataSubscriberInfo respDataSubInfo;
  private volatile DataSubscriberInfo grpcServerMethodSubInfo;
  private volatile DataSubscriberInfo grpcServerRequestMsgSubInfo;
  private volatile DataSubscriberInfo graphqlServerRequestMsgSubInfo;
  private volatile DataSubscriberInfo requestEndSubInfo;
  private volatile DataSubscriberInfo dbSqlQuerySubInfo;
  private volatile DataSubscriberInfo httpClientRequestSubInfo;
  private volatile DataSubscriberInfo httpClientResponseSubInfo;
  private volatile DataSubscriberInfo ioFileSubInfo;
  private volatile DataSubscriberInfo sessionIdSubInfo;
  private volatile DataSubscriberInfo userIdSubInfo;
  private final ConcurrentHashMap<String, DataSubscriberInfo> loginEventSubInfo =
      new ConcurrentHashMap<>();
  private volatile DataSubscriberInfo execCmdSubInfo;
  private volatile DataSubscriberInfo shellCmdSubInfo;

  public GatewayBridge(
      SubscriptionService subscriptionService,
      EventProducerService producerService,
      @Nonnull Supplier<ApiSecuritySampler> requestSamplerSupplier,
      List<TraceSegmentPostProcessor> traceSegmentPostProcessors) {
    this(
        subscriptionService,
        producerService,
        requestSamplerSupplier,
        null,
        traceSegmentPostProcessors);
  }

  GatewayBridge(
      SubscriptionService subscriptionService,
      EventProducerService producerService,
      @Nonnull Supplier<ApiSecuritySampler> requestSamplerSupplier,
      ApiSecurityDownstreamSampler downstreamSampler,
      List<TraceSegmentPostProcessor> traceSegmentPostProcessors) {
    this.subscriptionService = subscriptionService;
    this.producerService = producerService;
    this.requestSamplerSupplier = requestSamplerSupplier;
    this.downstreamSampler = downstreamSampler;
    this.traceSegmentPostProcessors = traceSegmentPostProcessors;
  }

  public void init() {
    Collection<datadog.trace.api.gateway.EventType<?>> additionalIGEvents =
        IGAppSecEventDependencies.additionalIGEventTypes(
            producerService.allSubscribedDataAddresses());

    subscriptionService.registerCallback(EVENTS.requestStarted(), this::onRequestStarted);
    subscriptionService.registerCallback(EVENTS.requestEnded(), this::onRequestEnded);
    subscriptionService.registerCallback(EVENTS.requestHeader(), this::onRequestHeader);
    subscriptionService.registerCallback(EVENTS.requestHeaderDone(), this::onRequestHeadersDone);
    subscriptionService.registerCallback(EVENTS.requestMethodUriRaw(), this::onRequestMethodUriRaw);
    subscriptionService.registerCallback(EVENTS.requestBodyStart(), this::onRequestBodyStart);
    subscriptionService.registerCallback(EVENTS.requestBodyDone(), this::onRequestBodyDone);
    subscriptionService.registerCallback(EVENTS.responseBody(), this::onResponseBody);
    subscriptionService.registerCallback(
        EVENTS.requestClientSocketAddress(), this::onRequestClientSocketAddress);
    subscriptionService.registerCallback(
        EVENTS.requestInferredClientAddress(), this::onRequestInferredClientAddress);
    subscriptionService.registerCallback(EVENTS.responseStarted(), this::onResponseStarted);
    subscriptionService.registerCallback(EVENTS.responseHeader(), this::onResponseHeader);
    subscriptionService.registerCallback(EVENTS.responseHeaderDone(), this::onResponseHeaderDone);
    subscriptionService.registerCallback(EVENTS.grpcServerMethod(), this::onGrpcServerMethod);
    subscriptionService.registerCallback(
        EVENTS.grpcServerRequestMessage(), this::onGrpcServerRequestMessage);
    subscriptionService.registerCallback(
        EVENTS.graphqlServerRequestMessage(), this::onGraphqlServerRequestMessage);
    subscriptionService.registerCallback(EVENTS.databaseConnection(), this::onDatabaseConnection);
    subscriptionService.registerCallback(EVENTS.databaseSqlQuery(), this::onDatabaseSqlQuery);
    subscriptionService.registerCallback(EVENTS.httpClientSampling(), this::onHttpClientSampling);
    subscriptionService.registerCallback(EVENTS.httpClientRequest(), this::onHttpClientRequest);
    subscriptionService.registerCallback(EVENTS.httpClientResponse(), this::onHttpClientResponse);
    subscriptionService.registerCallback(EVENTS.fileLoaded(), this::onFileLoaded);
    subscriptionService.registerCallback(EVENTS.requestSession(), this::onRequestSession);
    subscriptionService.registerCallback(EVENTS.execCmd(), this::onExecCmd);
    subscriptionService.registerCallback(EVENTS.shellCmd(), this::onShellCmd);
    subscriptionService.registerCallback(EVENTS.user(), this::onUser);
    subscriptionService.registerCallback(EVENTS.loginEvent(), this::onLoginEvent);
    subscriptionService.registerCallback(EVENTS.httpRoute(), this::onHttpRoute);

    if (additionalIGEvents.contains(EVENTS.requestPathParams())) {
      subscriptionService.registerCallback(EVENTS.requestPathParams(), this::onRequestPathParams);
    }
    if (additionalIGEvents.contains(EVENTS.requestBodyProcessed())) {
      subscriptionService.registerCallback(
          EVENTS.requestBodyProcessed(), this::onRequestBodyProcessed);
    }
  }

  /**
   * This method clears all the cached subscriptions, should be used everytime the configuration
   * changes and new addresses might appear or disappear from the config.
   */
  public void reset() {
    initialReqDataSubInfo = null;
    rawRequestBodySubInfo = null;
    requestBodySubInfo = null;
    responseBodySubInfo = null;
    pathParamsSubInfo = null;
    respDataSubInfo = null;
    grpcServerMethodSubInfo = null;
    grpcServerRequestMsgSubInfo = null;
    graphqlServerRequestMsgSubInfo = null;
    requestEndSubInfo = null;
    dbSqlQuerySubInfo = null;
    httpClientRequestSubInfo = null;
    httpClientResponseSubInfo = null;
    ioFileSubInfo = null;
    sessionIdSubInfo = null;
    userIdSubInfo = null;
    loginEventSubInfo.clear();
    execCmdSubInfo = null;
    shellCmdSubInfo = null;
  }

  private Flow<Void> onUser(final RequestContext ctx_, final String user) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    // update current context with new user id
    if (!ctx.updateUserId(user)) {
      return NoopFlow.INSTANCE;
    }

    // call waf if we have a new user id
    while (true) {
      DataSubscriberInfo subInfo = userIdSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.USER_ID);
        userIdSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new MapDataBundle.Builder(CAPACITY_0_2).add(KnownAddresses.USER_ID, user).build();
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        userIdSubInfo = null;
      }
    }
  }

  private void onHttpRoute(final RequestContext ctx_, final String route) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return;
    }
    ctx.setRoute(route);
  }

  private Flow<Void> onLoginEvent(
      final RequestContext ctx_, final LoginEvent event, final String login) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    // update current context with new user login
    if (!ctx.updateUserLogin(login)) {
      return NoopFlow.INSTANCE;
    }

    // call waf if we have a new user login
    final List<Address<?>> addresses = new ArrayList<>(2);
    final MapDataBundle.Builder bundleBuilder = new MapDataBundle.Builder(CAPACITY_0_2);
    addresses.add(KnownAddresses.USER_LOGIN);
    bundleBuilder.add(KnownAddresses.USER_LOGIN, login);

    // parse the event
    Address<?> address = EVENT_MAPPINGS.get(event);
    if (address != null) {
      addresses.add(address);
      // we don't support null values for the address so we use an invalid placeholder here
      bundleBuilder.add(address, "invalid");
    }
    final DataBundle bundle = bundleBuilder.build();
    final String subInfoKey =
        addresses.stream().map(Address::getKey).collect(Collectors.joining("|"));
    while (true) {
      DataSubscriberInfo subInfo =
          loginEventSubInfo.computeIfAbsent(
              subInfoKey,
              t -> producerService.getDataSubscribers(addresses.toArray(new Address[0])));
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        loginEventSubInfo.remove(subInfoKey);
      }
    }
  }

  private Flow<Void> onRequestSession(final RequestContext ctx_, final String sessionId) {
    final AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (sessionId == null || ctx == null) {
      return NoopFlow.INSTANCE;
    }
    if (sessionId.equals(ctx.getSessionId())) {
      return NoopFlow.INSTANCE;
    }
    // unlikely that multiple threads will update the value at the same time
    ctx.setSessionId(sessionId);
    while (true) {
      DataSubscriberInfo subInfo = sessionIdSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.SESSION_ID);
        sessionIdSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new MapDataBundle.Builder(CAPACITY_0_2).add(KnownAddresses.SESSION_ID, sessionId).build();
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        sessionIdSubInfo = null;
      }
    }
  }

  private Flow<Boolean> onHttpClientSampling(RequestContext ctx_, final long requestId) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return new Flow.ResultFlow<>(false);
    }
    return new Flow.ResultFlow<>(downstreamSampler().sampleHttpClientRequest(ctx, requestId));
  }

  private Flow<Void> onHttpClientRequest(RequestContext ctx_, HttpClientRequest request) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    final MapDataBundle.Builder bundleBuilder =
        new MapDataBundle.Builder(CAPACITY_3_4)
            .add(KnownAddresses.IO_NET_URL, request.getUrl())
            .add(KnownAddresses.IO_NET_REQUEST_METHOD, request.getMethod())
            .add(KnownAddresses.IO_NET_REQUEST_HEADERS, toLowerCaseHeaders(request.getHeaders()));

    if (downstreamSampler().isSampled(ctx, request.getRequestId())) {
      final Object body = parseHttpClientBody(ctx, request);
      if (body != null) {
        bundleBuilder.add(KnownAddresses.IO_NET_REQUEST_BODY, body);
      }
    }
    final DataBundle bundle = bundleBuilder.build();

    while (true) {
      DataSubscriberInfo subInfo = httpClientRequestSubInfo;
      if (subInfo == null) {
        subInfo =
            producerService.getDataSubscribers(
                KnownAddresses.IO_NET_URL,
                KnownAddresses.IO_NET_REQUEST_METHOD,
                KnownAddresses.IO_NET_REQUEST_HEADERS,
                KnownAddresses.IO_NET_REQUEST_BODY);
        httpClientRequestSubInfo = subInfo;
      }
      try {
        final boolean raspActive = InstrumenterConfig.get().isAppSecRaspEnabled();
        GatewayContext gwCtx = new GatewayContext(true, raspActive ? RuleType.SSRF_REQUEST : null);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        httpClientRequestSubInfo = null;
      }
    }
  }

  private Flow<Void> onHttpClientResponse(RequestContext ctx_, HttpClientResponse response) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    final MapDataBundle.Builder bundleBuilder =
        new MapDataBundle.Builder(CAPACITY_3_4)
            .add(KnownAddresses.IO_NET_RESPONSE_STATUS, Integer.toString(response.getStatus()))
            .add(KnownAddresses.IO_NET_RESPONSE_HEADERS, toLowerCaseHeaders(response.getHeaders()));
    // ignore the response if not sampled
    if (downstreamSampler().isSampled(ctx, response.getRequestId())) {
      final Object body = parseHttpClientBody(ctx, response);
      if (body != null) {
        bundleBuilder.add(KnownAddresses.IO_NET_RESPONSE_BODY, body);
      }
    }

    final DataBundle bundle = bundleBuilder.build();

    while (true) {
      DataSubscriberInfo subInfo = httpClientResponseSubInfo;
      if (subInfo == null) {
        subInfo =
            producerService.getDataSubscribers(
                KnownAddresses.IO_NET_RESPONSE_STATUS,
                KnownAddresses.IO_NET_RESPONSE_HEADERS,
                KnownAddresses.IO_NET_RESPONSE_BODY);
        httpClientResponseSubInfo = subInfo;
      }
      try {
        final boolean raspActive = InstrumenterConfig.get().isAppSecRaspEnabled();
        GatewayContext gwCtx = new GatewayContext(true, raspActive ? RuleType.SSRF_RESPONSE : null);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        httpClientResponseSubInfo = null;
      }
    }
  }

  private Map<String, List<String>> toLowerCaseHeaders(final Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return headers;
    }
    final Map<String, List<String>> result = new HashMap<>(headers.size());
    for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
      final String key = entry.getKey();
      final List<String> value = entry.getValue();
      result.put(key == null ? null : key.toLowerCase(Locale.ROOT), value);
    }
    return result;
  }

  private Object parseHttpClientBody(
      final AppSecRequestContext ctx, final HttpClientPayload payload) {
    if (payload.getContentType() == null || payload.getBody() == null) {
      return null;
    }

    final BodyParser parser = BodyParser.forMediaType(payload.getContentType());
    if (parser == null) {
      log.debug(SEND_TELEMETRY, "Received non parseable content type {}", payload.getContentType());
      return null;
    }
    final BodyParser.State state = new BodyParser.State();
    final Object result = parser.parse(state, payload.getBody());
    if (state.stringTooLong || state.listMapTooLarge || state.objectTooDeep) {
      ctx.setWafTruncated();
      WafMetricCollector.get()
          .wafInputTruncated(state.stringTooLong, state.listMapTooLarge, state.objectTooDeep);
    }
    return result;
  }

  private Flow<Void> onExecCmd(RequestContext ctx_, String[] command) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      DataSubscriberInfo subInfo = execCmdSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.EXEC_CMD);
        execCmdSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new MapDataBundle.Builder(CAPACITY_0_2).add(KnownAddresses.EXEC_CMD, command).build();
      try {
        GatewayContext gwCtx = new GatewayContext(true, RuleType.COMMAND_INJECTION);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        execCmdSubInfo = null;
      }
    }
  }

  private Flow<Void> onShellCmd(RequestContext ctx_, String command) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      DataSubscriberInfo subInfo = shellCmdSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.SHELL_CMD);
        shellCmdSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new MapDataBundle.Builder(CAPACITY_0_2).add(KnownAddresses.SHELL_CMD, command).build();
      try {
        GatewayContext gwCtx = new GatewayContext(true, RuleType.SHELL_INJECTION);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        shellCmdSubInfo = null;
      }
    }
  }

  private Flow<Void> onFileLoaded(RequestContext ctx_, String path) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      DataSubscriberInfo subInfo = ioFileSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.IO_FS_FILE);
        ioFileSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new MapDataBundle.Builder(CAPACITY_0_2).add(KnownAddresses.IO_FS_FILE, path).build();
      try {
        GatewayContext gwCtx = new GatewayContext(true, RuleType.LFI);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        ioFileSubInfo = null;
      }
    }
  }

  private Flow<Void> onDatabaseSqlQuery(RequestContext ctx_, String sql) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      DataSubscriberInfo subInfo = dbSqlQuerySubInfo;
      if (subInfo == null) {
        subInfo =
            producerService.getDataSubscribers(KnownAddresses.DB_TYPE, KnownAddresses.DB_SQL_QUERY);
        dbSqlQuerySubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new MapDataBundle.Builder(CAPACITY_0_2)
              .add(KnownAddresses.DB_TYPE, ctx.getDbType())
              .add(KnownAddresses.DB_SQL_QUERY, sql)
              .build();
      try {
        GatewayContext gwCtx = new GatewayContext(true, RuleType.SQL_INJECTION);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        dbSqlQuerySubInfo = null;
      }
    }
  }

  private void onDatabaseConnection(RequestContext ctx_, String dbType) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return;
    }
    ctx.setDbType(dbType);
  }

  private Flow<Void> onGraphqlServerRequestMessage(RequestContext ctx_, Map<String, ?> data) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      DataSubscriberInfo subInfo = graphqlServerRequestMsgSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.GRAPHQL_SERVER_ALL_RESOLVERS);
        graphqlServerRequestMsgSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle =
          new SingletonDataBundle<>(KnownAddresses.GRAPHQL_SERVER_ALL_RESOLVERS, data);
      try {
        GatewayContext gwCtx = new GatewayContext(true);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        graphqlServerRequestMsgSubInfo = null;
      }
    }
  }

  private Flow<Void> onGrpcServerRequestMessage(RequestContext ctx_, Object obj) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      DataSubscriberInfo subInfo = grpcServerRequestMsgSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE);
        grpcServerRequestMsgSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      Object convObj = ObjectIntrospection.convert(obj, ctx);
      DataBundle bundle =
          new SingletonDataBundle<>(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE, convObj);
      try {
        GatewayContext gwCtx = new GatewayContext(true);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        grpcServerRequestMsgSubInfo = null;
      }
    }
  }

  private Flow<Void> onGrpcServerMethod(RequestContext ctx_, String method) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || method == null || method.isEmpty()) {
      return NoopFlow.INSTANCE;
    }
    while (true) {
      DataSubscriberInfo subInfo = grpcServerMethodSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.GRPC_SERVER_METHOD);
        grpcServerMethodSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.GRPC_SERVER_METHOD, method);
      try {
        GatewayContext gwCtx = new GatewayContext(true);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        grpcServerMethodSubInfo = null;
      }
    }
  }

  private Flow<Void> onResponseHeaderDone(RequestContext ctx_) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRespDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.finishResponseHeaders();
    return maybePublishResponseData(ctx);
  }

  private void onResponseHeader(RequestContext ctx_, String name, String value) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx != null) {
      ctx.addResponseHeader(name, value);
    }
  }

  private Flow<Void> onResponseStarted(RequestContext ctx_, Integer status) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRespDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setResponseStatus(status);
    return maybePublishResponseData(ctx);
  }

  private NoopFlow onRequestInferredClientAddress(RequestContext ctx_, String ip) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx != null) {
      ctx.setInferredClientIp(ip);
    }
    return NoopFlow.INSTANCE;
  }

  private Flow<Void> onRequestClientSocketAddress(RequestContext ctx_, String ip, Integer port) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isReqDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setPeerAddress(ip);
    ctx.setPeerPort(port);
    return maybePublishRequestData(ctx);
  }

  private Flow<Void> onRequestBodyProcessed(RequestContext ctx_, Object obj) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    if (ctx.isConvertedReqBodyPublished()) {
      log.debug("Request body already published; will ignore new value of type {}", obj.getClass());
      return NoopFlow.INSTANCE;
    }
    ctx.setConvertedReqBodyPublished(true);

    while (true) {
      DataSubscriberInfo subInfo = requestBodySubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_OBJECT);
        requestBodySubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      Object converted =
          ObjectIntrospection.convert(
              obj,
              ctx,
              () -> {
                ctx.setProcessedResponseBodySizeExceeded(true);
              });
      ctx.setProcessedRequestBody(converted);
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_BODY_OBJECT, converted);
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        requestBodySubInfo = null;
      }
    }
  }

  private Flow<Void> onRequestBodyDone(RequestContext ctx_, StoredBodySupplier supplier) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isRawReqBodyPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setRawReqBodyPublished(true);

    while (true) {
      DataSubscriberInfo subInfo = rawRequestBodySubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_BODY_RAW);
        rawRequestBodySubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }

      CharSequence bodyContent = supplier.get();
      if (bodyContent == null || bodyContent.length() == 0) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_BODY_RAW, bodyContent);
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        rawRequestBodySubInfo = null;
      }
    }
  }

  private Flow<Void> onResponseBody(RequestContext ctx_, Object obj) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    if (ctx.isResponseBodyPublished()) {
      log.debug(
          "Response body already published; will ignore new value of type {}", obj.getClass());
      return NoopFlow.INSTANCE;
    }
    ctx.setResponseBodyPublished(true);

    while (true) {
      DataSubscriberInfo subInfo = responseBodySubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.RESPONSE_BODY_OBJECT);
        responseBodySubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      Object converted = ObjectIntrospection.convert(obj, ctx);
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.RESPONSE_BODY_OBJECT, converted);
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        responseBodySubInfo = null;
      }
    }
  }

  private Flow<Void> onRequestPathParams(RequestContext ctx_, Map<String, ?> data) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isPathParamsPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.setPathParamsPublished(true);

    while (true) {
      DataSubscriberInfo subInfo = pathParamsSubInfo;
      if (subInfo == null) {
        subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_PATH_PARAMS);
        pathParamsSubInfo = subInfo;
      }
      if (subInfo == null || subInfo.isEmpty()) {
        return NoopFlow.INSTANCE;
      }
      DataBundle bundle = new SingletonDataBundle<>(KnownAddresses.REQUEST_PATH_PARAMS, data);
      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        pathParamsSubInfo = null;
      }
    }
  }

  private Void onRequestBodyStart(RequestContext ctx_, StoredBodySupplier supplier) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return null;
    }

    ctx.setStoredRequestBodySupplier(supplier);
    return null;
  }

  private Flow<AppSecRequestContext> onRequestStarted() {
    if (!AppSecSystem.isActive()) {
      return RequestContextSupplier.EMPTY;
    }
    return new RequestContextSupplier();
  }

  private NoopFlow onRequestEnded(RequestContext ctx_, IGSpanInfo spanInfo) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }
    ctx.setRequestEndCalled();

    TraceSegment traceSeg = ctx_.getTraceSegment();
    Map<String, Object> tags = spanInfo.getTags();

    if (maybeSampleForApiSecurity(ctx, spanInfo, tags)) {
      if (!Config.get().isApmTracingEnabled()) {
        traceSeg.setTagTop(Tags.ASM_KEEP, true);
        traceSeg.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      }
    } else {
      ctx.closeWafContext();
    }

    // AppSec report metric and events for web span only
    if (traceSeg != null) {
      traceSeg.setTagTop("_dd.appsec.enabled", 1);
      traceSeg.setTagTop("_dd.runtime_family", "jvm");

      Collection<AppSecEvent> collectedEvents = ctx.transferCollectedEvents();

      for (TraceSegmentPostProcessor pp : this.traceSegmentPostProcessors) {
        pp.processTraceSegment(traceSeg, ctx, collectedEvents);
      }

      final int clientRequests = ctx.getHttpClientRequestCount();
      if (clientRequests > 0) {
        traceSeg.setTagTop("_dd.appsec.downstream_request", clientRequests);
      }

      // If detected any events - mark span at appsec.event
      if (!collectedEvents.isEmpty()) {
        if (ctx.isManuallyKept()) {
          // Set asm keep in case that root span was not available when events are detected
          traceSeg.setTagTop(Tags.ASM_KEEP, true);
          traceSeg.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
        }
        traceSeg.setTagTop("appsec.event", true);
        traceSeg.setTagTop("network.client.ip", ctx.getPeerAddress());

        // Reflect client_ip as actor.ip for backward compatibility
        Object clientIp = tags.get(Tags.HTTP_CLIENT_IP);
        if (clientIp != null) {
          traceSeg.setTagTop("actor.ip", clientIp);
        }

        // Report AppSec events via "_dd.appsec.json" tag
        AppSecEventWrapper wrapper = new AppSecEventWrapper(collectedEvents);
        traceSeg.setDataTop("appsec", wrapper);

        // Report collected request and response headers based on allow list
        boolean collectAll = ctx.isExtendedDataCollection();
        writeRequestHeaders(
            ctx, traceSeg, REQUEST_HEADERS_ALLOW_LIST, ctx.getRequestHeaders(), collectAll);
        writeResponseHeaders(
            ctx, traceSeg, RESPONSE_HEADERS_ALLOW_LIST, ctx.getResponseHeaders(), collectAll);

        // Report collected stack traces
        List<StackTraceEvent> stackTraces = ctx.getStackTraces();
        if (stackTraces != null && !stackTraces.isEmpty()) {
          StackUtils.addStacktraceEventsToMetaStruct(ctx_, METASTRUCT_EXPLOIT, stackTraces);
        }

        if (ctx.isExtendedDataCollection() && ctx.getProcessedRequestBody() != null) {
          ctx_.getOrCreateMetaStructTop(
              METASTRUCT_REQUEST_BODY, k -> ctx.getProcessedRequestBody());
          if (ctx.isProcessedResponseBodySizeExceeded()) {
            traceSeg.setTagTop("_dd.appsec.request_body_size.exceeded", true);
          }
        }

      } else if (hasUserInfo(traceSeg)) {
        // Report all collected request headers on user tracking event
        writeRequestHeaders(
            ctx, traceSeg, REQUEST_HEADERS_ALLOW_LIST, ctx.getRequestHeaders(), false);
      } else {
        // Report minimum set of collected request headers
        writeRequestHeaders(
            ctx, traceSeg, DEFAULT_REQUEST_HEADERS_ALLOW_LIST, ctx.getRequestHeaders(), false);
      }
      // If extracted any derivatives - commit them
      if (!ctx.commitDerivatives(traceSeg)) {
        log.debug("Unable to commit, derivatives will be skipped {}", ctx.getDerivativeKeys());
      }

      WafMetricCollector.get()
          .wafRequest(
              !collectedEvents.isEmpty(), // ruleTriggered
              ctx.isWafBlocked(), // requestBlocked
              ctx.hasWafErrors(), // wafError
              ctx.getWafTimeouts() > 0, // wafTimeout,
              ctx.isWafRequestBlockFailure(), // blockFailure,
              ctx.isWafRateLimited(), // rateLimited,
              ctx.isWafTruncated() // inputTruncated
              );
    }

    ctx.close();
    return NoopFlow.INSTANCE;
  }

  private boolean maybeSampleForApiSecurity(
      AppSecRequestContext ctx, IGSpanInfo spanInfo, Map<String, Object> tags) {
    log.debug("Checking API Security for end of request handler on span: {}", spanInfo.getSpanId());
    // API Security sampling requires http.route tag.
    final Object route = tags.get(Tags.HTTP_ROUTE);
    if (route != null) {
      ctx.setRoute(route.toString());
    }
    ApiSecuritySampler requestSampler = requestSamplerSupplier.get();
    return requestSampler.preSampleRequest(ctx);
  }

  private Flow<Void> onRequestHeadersDone(RequestContext ctx_) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || ctx.isReqDataPublished()) {
      return NoopFlow.INSTANCE;
    }
    ctx.finishRequestHeaders();
    return maybePublishRequestData(ctx);
  }

  private Flow<Void> onRequestMethodUriRaw(RequestContext ctx_, String method, URIDataAdapter uri) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return NoopFlow.INSTANCE;
    }

    if (ctx.isReqDataPublished()) {
      log.debug(
          "Request method and URI already published; will ignore new values {}, {}", method, uri);
      return NoopFlow.INSTANCE;
    }
    ctx.setMethod(method);
    ctx.setScheme(uri.scheme());
    if (uri.supportsRaw()) {
      ctx.setRawURI(uri.raw());
    } else {
      try {
        URI encodedUri = new URI(null, null, uri.path(), uri.query(), null);
        String q = encodedUri.getRawQuery();
        StringBuilder encoded = new StringBuilder();
        encoded.append(encodedUri.getRawPath());
        if (null != q && !q.isEmpty()) {
          encoded.append('?').append(q);
        }
        ctx.setRawURI(encoded.toString());
      } catch (URISyntaxException e) {
        log.debug("Failed to encode URI '{}{}'", uri.path(), uri.query());
      }
    }
    return maybePublishRequestData(ctx);
  }

  private void onRequestHeader(RequestContext ctx_, String name, String value) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null) {
      return;
    }

    if (name.equalsIgnoreCase("cookie")) {
      Map<String, List<String>> cookies = CookieCutter.parseCookieHeader(value);
      ctx.addCookies(cookies);
    } else {
      ctx.addRequestHeader(name, value);
    }
  }

  public void stop() {
    subscriptionService.reset();
  }

  private static boolean hasUserInfo(final TraceSegment traceSegment) {
    return hasUserTrackingEvent(traceSegment) || hasUserCollectionEvent(traceSegment);
  }

  private static boolean hasUserTrackingEvent(final TraceSegment traceSeg) {
    for (String tagName : USER_TRACKING_TAGS) {
      final Object value = traceSeg.getTagTop(tagName);
      if (value != null && "true".equalsIgnoreCase(value.toString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasUserCollectionEvent(final TraceSegment traceSeg) {
    return traceSeg.getTagTop(USER_COLLECTION_MODE_TAG) != null;
  }

  private static void writeRequestHeaders(
      AppSecRequestContext ctx,
      final TraceSegment traceSeg,
      final Set<String> allowed,
      final Map<String, List<String>> headers,
      final boolean collectAll) {
    writeHeaders(
        ctx,
        traceSeg,
        "http.request.headers.",
        "_dd.appsec.request.",
        allowed,
        headers,
        collectAll,
        true);
  }

  private static void writeResponseHeaders(
      AppSecRequestContext ctx,
      final TraceSegment traceSeg,
      final Set<String> allowed,
      final Map<String, List<String>> headers,
      final boolean collectAll) {
    writeHeaders(
        ctx,
        traceSeg,
        "http.response.headers.",
        "_dd.appsec.response.",
        allowed,
        headers,
        collectAll,
        false);
  }

  private static void writeHeaders(
      AppSecRequestContext ctx,
      final TraceSegment traceSeg,
      final String prefix,
      final String discardedPrefix,
      final Set<String> allowed,
      final Map<String, List<String>> headers,
      final boolean collectAll,
      final boolean checkCookie) {

    if (headers == null || headers.isEmpty()) {
      return;
    }

    final int headerLimit = ctx.getExtendedDataCollectionMaxHeaders();
    final Set<String> added = new HashSet<>();
    int excluded = 0;

    // Try to add allowed headers (prioritized)
    for (String name : allowed) {
      if (collectAll && added.size() >= headerLimit) {
        break;
      }
      List<String> values = headers.get(name);
      if (values != null) {
        String joined = String.join(",", values);
        if (!joined.isEmpty()) {
          traceSeg.setTagTop(prefix + name, joined);
          added.add(name);
        }
      }
    }

    if (collectAll) {
      // Add other headers (non-allowed) until total reaches the limit
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        String name = entry.getKey();
        if (added.contains(name)) {
          continue;
        }

        if (added.size() >= headerLimit) {
          excluded++;
          continue;
        }
        String joined;
        if (AUTHORIZATION_HEADERS.contains(name)) {
          joined = String.join(",", "<redacted>");
        } else {
          joined = String.join(",", entry.getValue());
        }
        if (!joined.isEmpty()) {
          traceSeg.setTagTop(prefix + name, joined);
          added.add(name);
        }
      }
      if (checkCookie && !ctx.getCookies().isEmpty()) {
        traceSeg.setTagTop(prefix + "cookie", "<redacted>");
      }

      if (excluded > 0) {
        traceSeg.setTagTop(discardedPrefix + "header_collection.discarded", excluded);
      }
    }
  }

  private static class RequestContextSupplier implements Flow<AppSecRequestContext> {
    private static final Flow<AppSecRequestContext> EMPTY = new RequestContextSupplier(null);

    private final AppSecRequestContext appSecRequestContext;

    public RequestContextSupplier() {
      this(new AppSecRequestContext());
    }

    public RequestContextSupplier(AppSecRequestContext ctx) {
      appSecRequestContext = ctx;
    }

    @Override
    public Action getAction() {
      return Action.Noop.INSTANCE;
    }

    @Override
    public AppSecRequestContext getResult() {
      return appSecRequestContext;
    }
  }

  private Flow<Void> maybePublishRequestData(AppSecRequestContext ctx) {
    String savedRawURI = ctx.getSavedRawURI();

    if (savedRawURI == null || !ctx.isFinishedRequestHeaders() || ctx.getPeerAddress() == null) {
      return NoopFlow.INSTANCE;
    }

    Map<String, List<String>> queryParams = EMPTY_QUERY_PARAMS;
    int i = savedRawURI.indexOf('?');
    if (i != -1) {
      String qs = savedRawURI.substring(i + 1);
      // ideally we'd have the query string as parsed by the server
      // or at the very least the encoding used by the server
      queryParams = parseQueryStringParams(qs, StandardCharsets.UTF_8);
    }

    String scheme = ctx.getScheme();
    if (scheme == null) {
      scheme = "http";
    }

    ctx.setReqDataPublished(true);

    MapDataBundle bundle =
        new MapDataBundle.Builder(CAPACITY_6_10)
            .add(KnownAddresses.HEADERS_NO_COOKIES, ctx.getRequestHeaders())
            .add(KnownAddresses.REQUEST_COOKIES, ctx.getCookies())
            .add(KnownAddresses.REQUEST_SCHEME, scheme)
            .add(KnownAddresses.REQUEST_METHOD, ctx.getMethod())
            .add(KnownAddresses.REQUEST_URI_RAW, savedRawURI)
            .add(KnownAddresses.REQUEST_QUERY, queryParams)
            .add(KnownAddresses.REQUEST_CLIENT_IP, ctx.getPeerAddress())
            .add(KnownAddresses.REQUEST_CLIENT_PORT, ctx.getPeerPort())
            .add(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, ctx.getInferredClientIp())
            .build();

    while (true) {
      DataSubscriberInfo subInfo = this.initialReqDataSubInfo;
      if (subInfo == null) {
        subInfo =
            producerService.getDataSubscribers(
                KnownAddresses.HEADERS_NO_COOKIES,
                KnownAddresses.REQUEST_COOKIES,
                KnownAddresses.REQUEST_SCHEME,
                KnownAddresses.REQUEST_METHOD,
                KnownAddresses.REQUEST_URI_RAW,
                KnownAddresses.REQUEST_QUERY,
                KnownAddresses.REQUEST_CLIENT_IP,
                KnownAddresses.REQUEST_CLIENT_PORT,
                KnownAddresses.REQUEST_INFERRED_CLIENT_IP);
        initialReqDataSubInfo = subInfo;
      }

      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        this.initialReqDataSubInfo = null;
      }
    }
  }

  private Flow<Void> maybePublishResponseData(AppSecRequestContext ctx) {

    int status = ctx.getResponseStatus();

    if (status == 0 || !ctx.isFinishedResponseHeaders()) {
      return NoopFlow.INSTANCE;
    }

    ctx.setRespDataPublished(true);

    MapDataBundle bundle =
        MapDataBundle.of(
            KnownAddresses.RESPONSE_STATUS, String.valueOf(ctx.getResponseStatus()),
            KnownAddresses.RESPONSE_HEADERS_NO_COOKIES, ctx.getResponseHeaders());

    while (true) {
      DataSubscriberInfo subInfo = respDataSubInfo;
      if (subInfo == null) {
        subInfo =
            producerService.getDataSubscribers(
                KnownAddresses.RESPONSE_STATUS, KnownAddresses.RESPONSE_HEADERS_NO_COOKIES);
        respDataSubInfo = subInfo;
      }

      try {
        GatewayContext gwCtx = new GatewayContext(false);
        return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
      } catch (ExpiredSubscriberInfoException e) {
        respDataSubInfo = null;
      }
    }
  }

  private ApiSecurityDownstreamSampler downstreamSampler() {
    if (downstreamSampler == null) {
      downstreamSampler = new ApiSecurityDownstreamSamplerImpl();
    }
    return downstreamSampler;
  }

  private static Map<String, List<String>> parseQueryStringParams(
      String queryString, Charset uriEncoding) {
    if (queryString == null) {
      return Collections.emptyMap();
    }

    Map<String, List<String>> result = new HashMap<>();

    String[] keyValues = QUERY_PARAM_SPLITTER.split(queryString);

    for (String keyValue : keyValues) {
      String[] kv = QUERY_PARAM_VALUE_SPLITTER.split(keyValue, 2);
      String value = kv.length > 1 ? urlDecode(kv[1], uriEncoding, true) : "";
      String key = urlDecode(kv[0], uriEncoding, true);
      List<String> strings = result.computeIfAbsent(key, k -> new ArrayList<>(1));
      strings.add(value);
    }

    return result;
  }

  private static String urlDecode(String str, Charset charset, boolean queryString) {
    return decodeString(str, charset, queryString, Integer.MAX_VALUE);
  }

  private static String decodeString(String str, Charset charset, boolean queryString, int limit) {
    byte[] bytes = str.getBytes(charset);
    int j = 0;
    for (int i = 0; i < bytes.length && j < limit; i++, j++) {
      int b = bytes[i];
      if (b == 0x25 /* % */) {
        if (i + 2 < bytes.length) {
          int val = byteToDigit(bytes[i + 2]);
          if (val >= 0) {
            val += 16 * byteToDigit(bytes[i + 1]);
            if (val >= 0) {
              i += 2;
              bytes[j] = (byte) val;
              continue;
            }
          }
        }
      } else if (b == 0x2b /* + */ && queryString) {
        bytes[j] = ' ';
        continue;
      }
      bytes[j] = (byte) b;
    }

    return new String(bytes, 0, j, charset);
  }

  private static int byteToDigit(byte b) {
    if (b >= 0x30 /* 0 */ && b <= 0x39 /* 9 */) {
      return b - 0x30;
    }
    if (b >= 0x41 /* A */ && b <= 0x46 /* F */) {
      return 10 + (b - 0x41);
    }
    if (b >= 0x61 /* a */ && b <= 0x66 /* f */) {
      return 10 + (b - 0x61);
    }
    return -1;
  }

  private static class IGAppSecEventDependencies {

    private static final Map<Address<?>, Collection<datadog.trace.api.gateway.EventType<?>>>
        DATA_DEPENDENCIES = new HashMap<>(4);

    static {
      DATA_DEPENDENCIES.put(
          KnownAddresses.REQUEST_BODY_RAW, l(EVENTS.requestBodyStart(), EVENTS.requestBodyDone()));
      DATA_DEPENDENCIES.put(KnownAddresses.REQUEST_PATH_PARAMS, l(EVENTS.requestPathParams()));
      DATA_DEPENDENCIES.put(KnownAddresses.REQUEST_BODY_OBJECT, l(EVENTS.requestBodyProcessed()));
    }

    private static Collection<datadog.trace.api.gateway.EventType<?>> l(
        datadog.trace.api.gateway.EventType<?>... events) {
      return Arrays.asList(events);
    }

    static Collection<datadog.trace.api.gateway.EventType<?>> additionalIGEventTypes(
        Collection<Address<?>> addresses) {
      Set<datadog.trace.api.gateway.EventType<?>> res = new HashSet<>();
      for (Address<?> address : addresses) {
        Collection<datadog.trace.api.gateway.EventType<?>> c = DATA_DEPENDENCIES.get(address);
        if (c != null) {
          res.addAll(c);
        }
      }
      return res;
    }
  }
}
