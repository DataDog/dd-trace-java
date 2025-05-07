package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.context.Context.root;
import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.api.datastreams.DataStreamsContext.forHttpServer;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.context.InferredProxyContext;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.http.ClientIpAddressResolver;
import java.net.InetAddress;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpServerDecorator<REQUEST, CONNECTION, RESPONSE, REQUEST_CARRIER>
    extends ServerDecorator {

  class InferredProxySpanGroup implements AgentSpan {
    private final AgentSpan inferredProxySpan;
    private final AgentSpan serverSpan;

    InferredProxySpanGroup(AgentSpan inferredProxySpan, AgentSpan serverSpan) {
      this.inferredProxySpan = inferredProxySpan;
      this.serverSpan = serverSpan;
    }

    @Override
    public DDTraceId getTraceId() {
      return serverSpan.getTraceId();
    }

    @Override
    public long getSpanId() {
      return serverSpan.getSpanId();
    }

    @Override
    public AgentSpan setTag(String key, boolean value) {
      return serverSpan.setTag(key, value);
    }

    @Override
    public AgentSpan setTag(String key, int value) {
      return serverSpan.setTag(key, value);
    }

    @Override
    public AgentSpan setTag(String key, long value) {
      return serverSpan.setTag(key, value);
    }

    @Override
    public AgentSpan setTag(String key, double value) {
      return serverSpan.setTag(key, value);
    }

    @Override
    public AgentSpan setTag(String key, String value) {
      return serverSpan.setTag(key, value);
    }

    @Override
    public AgentSpan setTag(String key, CharSequence value) {
      return serverSpan.setTag(key, value);
    }

    @Override
    public AgentSpan setTag(String key, Object value) {
      return serverSpan.setTag(key, value);
    }

    /**
     * @param map
     * @return
     */
    @Override
    public AgentSpan setAllTags(Map<String, ?> map) {
      return null;
    }

    @Override
    public AgentSpan setTag(String key, Number value) {
      return serverSpan.setTag(key, value);
    }

    @Override
    public AgentSpan setMetric(CharSequence key, int value) {
      return serverSpan.setMetric(key, value);
    }

    @Override
    public AgentSpan setMetric(CharSequence key, long value) {
      return serverSpan.setMetric(key, value);
    }

    @Override
    public AgentSpan setMetric(CharSequence key, double value) {
      return serverSpan.setMetric(key, value);
    }

    @Override
    public AgentSpan setSpanType(CharSequence type) {
      return serverSpan.setSpanType(type);
    }

    @Override
    public Object getTag(String key) {
      return serverSpan.getTag(key);
    }

    @Override
    public AgentSpan setError(boolean error) {
      serverSpan.setError(error);
      if (inferredProxySpan != null) {
        inferredProxySpan.setError(error);
      }
      return this;
    }

    @Override
    public AgentSpan setError(boolean error, byte priority) {
      serverSpan.setError(error, priority);
      if (inferredProxySpan != null) {
        inferredProxySpan.setError(error, priority);
      }
      return this;
    }

    @Override
    public AgentSpan setMeasured(boolean measured) {
      return serverSpan.setMeasured(measured);
    }

    @Override
    public AgentSpan setErrorMessage(String errorMessage) {
      return serverSpan.setErrorMessage(errorMessage);
    }

    @Override
    public AgentSpan addThrowable(Throwable throwable) {
      serverSpan.addThrowable(throwable);
      if (inferredProxySpan != null) {
        inferredProxySpan.addThrowable(throwable);
      }
      return this;
    }

    @Override
    public AgentSpan addThrowable(Throwable throwable, byte errorPriority) {
      serverSpan.addThrowable(throwable, errorPriority);
      if (inferredProxySpan != null) {
        inferredProxySpan.addThrowable(throwable, errorPriority);
      }
      return this;
    }

    @Override
    public AgentSpan getLocalRootSpan() {
      return serverSpan.getLocalRootSpan();
    }

    @Override
    public boolean isSameTrace(AgentSpan otherSpan) {
      return serverSpan.isSameTrace(otherSpan);
    }

    @Override
    public AgentSpanContext context() {
      return serverSpan.context();
    }

    @Override
    public String getBaggageItem(String key) {
      return serverSpan.getBaggageItem(key);
    }

    @Override
    public AgentSpan setBaggageItem(String key, String value) {
      return serverSpan.setBaggageItem(key, value);
    }

    @Override
    public AgentSpan setHttpStatusCode(int statusCode) {
      serverSpan.setHttpStatusCode(statusCode);
      if (inferredProxySpan != null) {
        inferredProxySpan.setHttpStatusCode(statusCode);
      }
      return this;
    }

    @Override
    public short getHttpStatusCode() {
      return serverSpan.getHttpStatusCode();
    }

    @Override
    public void finish() {
      serverSpan.finish();
      if (inferredProxySpan != null) {
        inferredProxySpan.finish();
      }
    }

    @Override
    public void finish(long finishMicros) {
      serverSpan.finish(finishMicros);
      if (inferredProxySpan != null) {
        inferredProxySpan.finish(finishMicros);
      }
    }

    @Override
    public void finishWithDuration(long durationNanos) {
      serverSpan.finishWithDuration(durationNanos);
      if (inferredProxySpan != null) {
        inferredProxySpan.finishWithDuration(durationNanos);
      }
    }

    @Override
    public void beginEndToEnd() {
      serverSpan.beginEndToEnd();
    }

    @Override
    public void finishWithEndToEnd() {
      serverSpan.finishWithEndToEnd();
      if (inferredProxySpan != null) {
        inferredProxySpan.finishWithEndToEnd();
      }
    }

    @Override
    public boolean phasedFinish() {
      final boolean ret = serverSpan.phasedFinish();
      if (inferredProxySpan != null) {
        inferredProxySpan.phasedFinish();
      }
      return ret;
    }

    @Override
    public void publish() {
      serverSpan.publish();
    }

    @Override
    public CharSequence getSpanName() {
      return serverSpan.getSpanName();
    }

    @Override
    public void setSpanName(CharSequence spanName) {
      serverSpan.setSpanName(spanName);
    }

    @Deprecated
    @Override
    public boolean hasResourceName() {
      return serverSpan.hasResourceName();
    }

    @Override
    public byte getResourceNamePriority() {
      return serverSpan.getResourceNamePriority();
    }

    @Override
    public AgentSpan setResourceName(CharSequence resourceName) {
      return serverSpan.setResourceName(resourceName);
    }

    @Override
    public AgentSpan setResourceName(CharSequence resourceName, byte priority) {
      return serverSpan.setResourceName(resourceName, priority);
    }

    @Override
    public RequestContext getRequestContext() {
      return serverSpan.getRequestContext();
    }

    @Override
    public Integer forceSamplingDecision() {
      return serverSpan.forceSamplingDecision();
    }

    @Override
    public AgentSpan setSamplingPriority(int newPriority, int samplingMechanism) {
      return serverSpan.setSamplingPriority(newPriority, samplingMechanism);
    }

    @Override
    public TraceConfig traceConfig() {
      return serverSpan.traceConfig();
    }

    @Override
    public void addLink(AgentSpanLink link) {
      serverSpan.addLink(link);
    }

    @Override
    public AgentSpan setMetaStruct(String field, Object value) {
      return serverSpan.setMetaStruct(field, value);
    }

    @Override
    public boolean isOutbound() {
      return serverSpan.isOutbound();
    }

    @Override
    public AgentSpan asAgentSpan() {
      return serverSpan.asAgentSpan();
    }

    @Override
    public long getStartTime() {
      return serverSpan.getStartTime();
    }

    @Override
    public long getDurationNano() {
      return serverSpan.getDurationNano();
    }

    @Override
    public CharSequence getOperationName() {
      return serverSpan.getOperationName();
    }

    @Override
    public MutableSpan setOperationName(CharSequence serviceName) {
      return serverSpan.setOperationName(serviceName);
    }

    @Override
    public String getServiceName() {
      return serverSpan.getServiceName();
    }

    @Override
    public MutableSpan setServiceName(String serviceName) {
      return serverSpan.setServiceName(serviceName);
    }

    @Override
    public CharSequence getResourceName() {
      return serverSpan.getResourceName();
    }

    @Override
    public Integer getSamplingPriority() {
      return serverSpan.getSamplingPriority();
    }

    @Deprecated
    @Override
    public MutableSpan setSamplingPriority(int newPriority) {
      return serverSpan.setSamplingPriority(newPriority);
    }

    @Override
    public String getSpanType() {
      return serverSpan.getSpanType();
    }

    @Override
    public Map<String, Object> getTags() {
      return serverSpan.getTags();
    }

    @Override
    public boolean isError() {
      return serverSpan.isError();
    }

    @Deprecated
    @Override
    public MutableSpan getRootSpan() {
      return serverSpan.getRootSpan();
    }

    @Override
    public void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba) {
      serverSpan.setRequestBlockingAction(rba);
    }

    @Override
    public Flow.Action.RequestBlockingAction getRequestBlockingAction() {
      return serverSpan.getRequestBlockingAction();
    }
  }

  private static final Logger log = LoggerFactory.getLogger(HttpServerDecorator.class);
  private static final int UNSET_PORT = 0;

  public static final String PROXY_SYSTEM = "x-dd-proxy";
  public static final String PROXY_START_TIME_MS = "x-dd-proxy-request-time-ms";
  public static final String PROXY_PATH = "x-dd-proxy-path";
  public static final String PROXY_HTTP_METHOD = "x-dd-proxy-httpmethod";
  public static final String PROXY_DOMAIN_NAME = "x-dd-proxy-domain-name";
  public static final String STAGE = "x-dd-proxy-stage";

  public static final Map<String, String> SUPPORTED_PROXIES;

  static {
    SUPPORTED_PROXIES = new HashMap<>();
    SUPPORTED_PROXIES.put("aws-apigateway", "aws.apigateway");
  }

  public static final String DD_SPAN_ATTRIBUTE = "datadog.span";
  public static final String DD_DISPATCH_SPAN_ATTRIBUTE = "datadog.span.dispatch";
  public static final String DD_RUM_INJECTED = "datadog.rum.injected";
  public static final String DD_FIN_DISP_LIST_SPAN_ATTRIBUTE =
      "datadog.span.finish_dispatch_listener";
  public static final String DD_RESPONSE_ATTRIBUTE = "datadog.response";
  public static final String DD_IGNORE_COMMIT_ATTRIBUTE = "datadog.commit.ignore";

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");
  protected static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");
  protected static final boolean SHOULD_SET_404_RESOURCE_NAME =
      Config.get().isRuleEnabled("URLAsResourceNameRule")
          && Config.get().isRuleEnabled("Status404Rule")
          && Config.get().isRuleEnabled("Status404Decorator");
  private static final boolean SHOULD_SET_URL_RESOURCE_NAME =
      Config.get().isRuleEnabled("URLAsResourceNameRule");

  private static final BitSet SERVER_ERROR_STATUSES = Config.get().getHttpServerErrorStatuses();
  private static final String DEFAULT_INSTRUMENTATION_NAME = "http-server";

  private final boolean traceClientIpResolverEnabled =
      Config.get().isTraceClientIpResolverEnabled();

  protected abstract AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter();

  protected abstract AgentPropagation.ContextVisitor<RESPONSE> responseGetter();

  public abstract CharSequence spanName();

  protected abstract String method(REQUEST request);

  protected abstract URIDataAdapter url(REQUEST request);

  protected abstract String peerHostIP(CONNECTION connection);

  protected abstract int peerPort(CONNECTION connection);

  protected abstract int status(RESPONSE response);

  protected String requestedSessionId(REQUEST request) {
    return null;
  }

  public CharSequence operationName() {
    return SpanNaming.instance()
        .namingSchema()
        .server()
        .operationForComponent(component().toString());
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_SERVER;
  }

  @Override
  protected boolean traceAnalyticsDefault() {
    return Config.get().isTraceAnalyticsEnabled();
  }

  // Extract this to allow for easier testing
  protected AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }

  /**
   * Extracts context from an upstream service.
   *
   * @param carrier The request carrier to get the context from.
   * @return The extracted context, {@code Context#root()} if no valid context to extract.
   */
  public Context extract(REQUEST_CARRIER carrier) {
    AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter = getter();
    if (null == carrier || null == getter) {
      return root();
    }
    return Propagators.defaultPropagator().extract(root(), carrier, getter);
  }

  /**
   * Starts a span.
   *
   * @param carrier The request carrier.
   * @param parentContext The parent context of the span to create.
   * @return A new context bundling the span, child of the given parent context.
   */
  public Context startSpan(REQUEST_CARRIER carrier, Context parentContext) {
    String[] instrumentationNames = instrumentationNames();
    String instrumentationName =
        instrumentationNames != null && instrumentationNames.length > 0
            ? instrumentationNames[0]
            : DEFAULT_INSTRUMENTATION_NAME;
    AgentSpanContext.Extracted extracted =
        callIGCallbackStart(getExtractedSpanContext(parentContext));
    AgentSpan span =
        tracer().startSpan(instrumentationName, spanName(), extracted).setMeasured(true);
    Flow<Void> flow = callIGCallbackRequestHeaders(span, carrier);
    if (flow.getAction() instanceof RequestBlockingAction) {
      span.setRequestBlockingAction((RequestBlockingAction) flow.getAction());
    }
    AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter = getter();
    if (null != carrier && null != getter) {
      tracer().getDataStreamsMonitoring().setCheckpoint(span, forHttpServer());
    }
    return parentContext.with(span);
  }

  private AgentSpan startSpanWithInferredProxy(
      String instrumentationName,
      datadog.context.Context fullContextForInferredProxy,
      AgentSpanContext.Extracted standardExtractedContext) {

    InferredProxyContext inferredProxy =
        InferredProxyContext.fromContext(fullContextForInferredProxy);

    if (inferredProxy == null) {
      return null;
    }

    Map<String, String> headers = inferredProxy.getInferredProxyContext();

    // Check if timestamp and proxy system are present
    String startTimeStr = headers.get(PROXY_START_TIME_MS);
    String proxySystem = headers.get(PROXY_SYSTEM);

    if (startTimeStr == null
        || proxySystem == null
        || !SUPPORTED_PROXIES.containsKey(proxySystem)) {
      return null;
    }

    long startTime;
    try {
      startTime = Long.parseLong(startTimeStr) * 1000; // Convert to microseconds
    } catch (NumberFormatException e) {
      return null; // Invalid timestamp
    }

    AgentSpan apiGtwSpan =
        tracer()
            .startSpan(
                "inferred_proxy",
                SUPPORTED_PROXIES.get(proxySystem),
                callIGCallbackStart(standardExtractedContext),
                startTime);

    apiGtwSpan.setTag(Tags.COMPONENT, proxySystem);
    apiGtwSpan.setTag(
        DDTags.RESOURCE_NAME, headers.get(PROXY_HTTP_METHOD) + " " + headers.get(PROXY_PATH));
    apiGtwSpan.setTag(DDTags.SERVICE_NAME, headers.get(PROXY_DOMAIN_NAME));
    apiGtwSpan.setTag(DDTags.SPAN_TYPE, "web");
    apiGtwSpan.setTag(Tags.HTTP_METHOD, headers.get(PROXY_HTTP_METHOD));
    apiGtwSpan.setTag(Tags.HTTP_URL, headers.get(PROXY_DOMAIN_NAME) + headers.get(PROXY_PATH));
    apiGtwSpan.setTag("stage", headers.get(STAGE));
    apiGtwSpan.setTag("_dd.inferred_span", 1);
    return apiGtwSpan;
  }

  public AgentSpan onRequest(
      final AgentSpan span,
      final CONNECTION connection,
      final REQUEST request,
      final Context parentContext) {
    Config config = Config.get();

    if (APPSEC_ACTIVE) {
      RequestContext requestContext = span.getRequestContext();
      if (requestContext != null) {
        BlockResponseFunction brf = createBlockResponseFunction(request, connection);
        if (brf != null) {
          requestContext.setBlockResponseFunction(brf);
        }
      }
      Flow<Void> flow = callIGCallbackRequestSessionId(span, request);
      Flow.Action action = flow.getAction();
      if (action instanceof RequestBlockingAction) {
        span.setRequestBlockingAction((RequestBlockingAction) flow.getAction());
      }
    }

    AgentSpanContext.Extracted extracted = getExtractedSpanContext(parentContext);
    boolean clientIpResolverEnabled =
        config.isClientIpEnabled() || traceClientIpResolverEnabled && APPSEC_ACTIVE;
    if (extracted != null) {
      if (clientIpResolverEnabled) {
        String forwarded = extracted.getForwarded();
        if (forwarded != null) {
          span.setTag(Tags.HTTP_FORWARDED, forwarded);
        }
        String forwardedProto = extracted.getXForwardedProto();
        if (forwardedProto != null) {
          span.setTag(Tags.HTTP_FORWARDED_PROTO, forwardedProto);
        }
        String forwardedHost = extracted.getXForwardedHost();
        if (forwardedHost != null) {
          span.setTag(Tags.HTTP_FORWARDED_HOST, forwardedHost);
        }
        String forwardedIp = extracted.getXForwardedFor();
        if (forwardedIp != null) {
          span.setTag(Tags.HTTP_FORWARDED_IP, forwardedIp);
        }
        String forwardedPort = extracted.getXForwardedPort();
        if (forwardedPort != null) {
          span.setTag(Tags.HTTP_FORWARDED_PORT, forwardedPort);
        }
      }
      String userAgent = extracted.getUserAgent();
      if (userAgent != null) {
        span.setTag(Tags.HTTP_USER_AGENT, userAgent);
      }
    }

    if (request != null) {
      String method = method(request);
      span.setTag(Tags.HTTP_METHOD, method);

      // Copy of HttpClientDecorator url handling
      try {
        final URIDataAdapter url = url(request);
        if (url != null) {
          boolean supportsRaw = url.supportsRaw();
          boolean encoded = supportsRaw && config.isHttpServerRawResource();
          boolean valid = url.isValid();
          String path = encoded ? url.rawPath() : url.path();
          if (valid) {
            span.setTag(
                Tags.HTTP_URL, URIUtils.lazyValidURL(url.scheme(), url.host(), url.port(), path));
          } else if (supportsRaw) {
            span.setTag(Tags.HTTP_URL, URIUtils.lazyInvalidUrl(url.raw()));
          }
          if (extracted != null && extracted.getXForwardedHost() != null) {
            span.setTag(Tags.HTTP_HOSTNAME, extracted.getXForwardedHost());
          } else if (url.host() != null) {
            span.setTag(Tags.HTTP_HOSTNAME, url.host());
          }

          if (valid && config.isHttpServerTagQueryString()) {
            String query =
                supportsRaw && config.isHttpServerRawQueryString() ? url.rawQuery() : url.query();
            span.setTag(DDTags.HTTP_QUERY, query);
            span.setTag(DDTags.HTTP_FRAGMENT, url.fragment());
          }
          Flow<Void> flow = callIGCallbackURI(span, url, method);
          if (flow.getAction() instanceof RequestBlockingAction) {
            span.setRequestBlockingAction((RequestBlockingAction) flow.getAction());
          }
          if (valid && SHOULD_SET_URL_RESOURCE_NAME) {
            HTTP_RESOURCE_DECORATOR.withServerPath(span, method, path, encoded);
          }
        } else if (SHOULD_SET_URL_RESOURCE_NAME) {
          span.setResourceName(DEFAULT_RESOURCE_NAME);
        }
      } catch (final Exception e) {
        log.debug("Error tagging url", e);
      }
    }

    String peerIp = null;
    int peerPort = UNSET_PORT;
    if (connection != null) {
      peerIp = peerHostIP(connection);
      peerPort = peerPort(connection);
    }

    String inferredAddressStr = null;
    if (clientIpResolverEnabled && extracted != null) {
      InetAddress inferredAddress = ClientIpAddressResolver.resolve(extracted, span);
      // the peer address should be used if:
      // 1. the headers yield nothing, regardless of whether it is public or not
      // 2. it is public and the headers yield a private address
      if (peerIp != null) {
        if (inferredAddress == null) {
          inferredAddress = ClientIpAddressResolver.parseIpAddress(peerIp);
        } else if (ClientIpAddressResolver.isIpAddrPrivate(inferredAddress)) {
          InetAddress peerAddress = ClientIpAddressResolver.parseIpAddress(peerIp);
          if (!ClientIpAddressResolver.isIpAddrPrivate(peerAddress)) {
            inferredAddress = peerAddress;
          }
        }
      }
      if (inferredAddress != null) {
        inferredAddressStr = inferredAddress.getHostAddress();
        span.setTag(Tags.HTTP_CLIENT_IP, inferredAddressStr);
      }
    } else if (clientIpResolverEnabled && span.getLocalRootSpan() != span) {
      // in this case extracted == null
      // If there is no extracted we can't do anything but use the peer addr.
      // Additionally, extracted == null arises on subspans for which the resolution
      // likely already happened on the top span, so we don't need to do the resolution
      // again. Instead, copy from the top span, should it exist
      AgentSpan localRootSpan = span.getLocalRootSpan();
      Object clientIp = localRootSpan.getTag(Tags.HTTP_CLIENT_IP);
      if (clientIp != null) {
        span.setTag(Tags.HTTP_CLIENT_IP, clientIp);
      }
    }

    if (peerIp != null) {
      if (peerIp.indexOf(':') > 0) {
        span.setTag(Tags.PEER_HOST_IPV6, peerIp);
      } else {
        span.setTag(Tags.PEER_HOST_IPV4, peerIp);
      }
    }
    setPeerPort(span, peerPort);
    Flow<Void> flow = callIGCallbackAddressAndPort(span, peerIp, peerPort, inferredAddressStr);
    if (flow.getAction() instanceof RequestBlockingAction) {
      span.setRequestBlockingAction((RequestBlockingAction) flow.getAction());
    }

    return span;
  }

  protected static AgentSpanContext.Extracted getExtractedSpanContext(Context parentContext) {
    AgentSpan extractedSpan = AgentSpan.fromContext(parentContext);
    if (extractedSpan != null) {
      AgentSpanContext extractedSpanContext = extractedSpan.context();
      if (extractedSpanContext instanceof AgentSpanContext.Extracted) {
        return (AgentSpanContext.Extracted) extractedSpanContext;
      } else {
        log.warn("Expected AgentSpanContext.Extracted but found {}", extractedSpanContext);
      }
    }
    return null;
  }

  protected BlockResponseFunction createBlockResponseFunction(
      REQUEST request, CONNECTION connection) {
    return null;
  }

  public AgentSpan onResponseStatus(final AgentSpan span, final int status) {
    if (status > UNSET_STATUS) {
      span.setHttpStatusCode(status);
      // explicitly set here because some other decorators might already set an error without
      // looking at the status code
      // XXX: the logic is questionable: span.error becomes equivalent to status 5xx,
      // even if the server chooses not to respond with 5xx to an error.
      // Anyway, we def don't want it applied to blocked requests
      if (!BlockingException.class.getName().equals(span.getTag("error.type"))) {
        span.setError(SERVER_ERROR_STATUSES.get(status), ErrorPriorities.HTTP_SERVER_DECORATOR);
      }
    }

    if (SHOULD_SET_404_RESOURCE_NAME && status == 404) {
      span.setResourceName(NOT_FOUND_RESOURCE_NAME, ResourceNamePriorities.HTTP_404);
    }
    return span;
  }

  /**
   * Whether AppSec should NOT be called during onResponse() for analysis of the status code and
   * headers.
   *
   * <p>{@link #onResponse(AgentSpan, Object)} is usually called too late for AppSec to be able to
   * alter the response, so for those modules where we support blocking on response this is <code>
   * true</code> and AppSec has its own (earlier) hook point for processing the response (just
   * before commit).
   *
   * @return whether AppSec analysis of the response is run separately from onResponse
   */
  protected boolean isAppSecOnResponseSeparate() {
    return false;
  }

  public AgentSpan onResponse(final AgentSpan span, final RESPONSE response) {
    if (response != null) {
      final int status = status(response);
      onResponseStatus(span, status);

      AgentPropagation.ContextVisitor<RESPONSE> getter = responseGetter();
      if (getter != null) {
        ResponseHeaderTagClassifier tagger =
            ResponseHeaderTagClassifier.create(span, traceConfig(span).getResponseHeaderTags());
        if (tagger != null) {
          getter.forEachKey(response, tagger);
        }
      }

      if (!isAppSecOnResponseSeparate()) {
        callIGCallbackResponseAndHeaders(span, response, status);
      }
    }
    return span;
  }

  private AgentSpanContext.Extracted callIGCallbackStart(
      @Nullable final AgentSpanContext.Extracted extracted) {
    AgentTracer.TracerAPI tracer = tracer();
    Supplier<Flow<Object>> startedCbAppSec =
        tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestStarted());
    Supplier<Flow<Object>> startedCbIast =
        tracer.getCallbackProvider(RequestContextSlot.IAST).getCallback(EVENTS.requestStarted());

    if (startedCbAppSec == null && startedCbIast == null) {
      return extracted;
    }

    TagContext tagContext = null;
    if (extracted == null) {
      tagContext = new TagContext();
    } else if (extracted instanceof TagContext) {
      tagContext = (TagContext) extracted;
    }

    if (tagContext != null) {
      if (startedCbAppSec != null) {
        tagContext.withRequestContextDataAppSec(startedCbAppSec.get().getResult());
      }
      if (startedCbIast != null) {
        tagContext.withRequestContextDataIast(startedCbIast.get().getResult());
      }
      return tagContext;
    }

    return extracted;
  }

  @Override
  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    if (throwable != null) {
      span.addThrowable(
          throwable instanceof ExecutionException ? throwable.getCause() : throwable,
          ErrorPriorities.HTTP_SERVER_DECORATOR);
    }
    return span;
  }

  private Flow<Void> callIGCallbackRequestHeaders(AgentSpan span, REQUEST_CARRIER carrier) {
    CallbackProvider cbp = tracer().getUniversalCallbackProvider();
    RequestContext requestContext = span.getRequestContext();
    AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter = getter();
    if (requestContext == null || getter == null) {
      return Flow.ResultFlow.empty();
    }
    if (cbp != null) {
      IGKeyClassifier igKeyClassifier =
          IGKeyClassifier.create(
              requestContext,
              cbp.getCallback(EVENTS.requestHeader()),
              cbp.getCallback(EVENTS.requestHeaderDone()));
      if (null != igKeyClassifier) {
        getter.forEachKey(carrier, igKeyClassifier);
        return igKeyClassifier.done();
      }
    }
    return Flow.ResultFlow.empty();
  }

  @SuppressWarnings("UnusedReturnValue")
  private Flow<Void> callIGCallbackRequestSessionId(final AgentSpan span, final REQUEST request) {
    final String sessionId = requestedSessionId(request);
    if (sessionId == null) {
      return Flow.ResultFlow.empty();
    }
    final CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    final RequestContext requestContext = span.getRequestContext();
    if (cbp == null || requestContext == null) {
      return Flow.ResultFlow.empty();
    }
    final BiFunction<RequestContext, String, Flow<Void>> addrCallback =
        cbp.getCallback(EVENTS.requestSession());
    if (addrCallback == null) {
      return Flow.ResultFlow.empty();
    }
    return addrCallback.apply(requestContext, sessionId);
  }

  private Flow<Void> callIGCallbackResponseAndHeaders(
      AgentSpan span, RESPONSE carrier, int status) {
    return callIGCallbackResponseAndHeaders(span, carrier, status, responseGetter());
  }

  public <RESP> Flow<Void> callIGCallbackResponseAndHeaders(
      AgentSpan span,
      RESP carrier,
      int status,
      AgentPropagation.ContextVisitor<RESP> contextVisitor) {
    CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    RequestContext requestContext = span.getRequestContext();
    if (cbp == null || requestContext == null) {
      return Flow.ResultFlow.empty();
    }

    BiFunction<RequestContext, Integer, Flow<Void>> addrCallback =
        cbp.getCallback(EVENTS.responseStarted());
    if (null != addrCallback) {
      addrCallback.apply(requestContext, status);
    }
    if (contextVisitor == null) {
      return Flow.ResultFlow.empty();
    }
    IGKeyClassifier igKeyClassifier =
        IGKeyClassifier.create(
            requestContext,
            cbp.getCallback(EVENTS.responseHeader()),
            cbp.getCallback(EVENTS.responseHeaderDone()));
    if (null != igKeyClassifier) {
      contextVisitor.forEachKey(carrier, igKeyClassifier);
      return igKeyClassifier.done();
    }
    return Flow.ResultFlow.empty();
  }

  private Flow<Void> callIGCallbackURI(
      @Nonnull final AgentSpan span, @Nonnull final URIDataAdapter url, final String method) {
    // TODO:appsec there must be some better way to do this?
    CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null || cbp == null) {
      return Flow.ResultFlow.empty();
    }

    TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestMethodUriRaw());
    if (callback != null) {
      return callback.apply(requestContext, method, url);
    }
    return Flow.ResultFlow.empty();
  }

  @Override
  public AgentSpan beforeFinish(AgentSpan span) {
    onRequestEndForInstrumentationGateway(span);
    return super.beforeFinish(span);
  }

  private void onRequestEndForInstrumentationGateway(@Nonnull final AgentSpan span) {
    if (span.getLocalRootSpan() != span) {
      return;
    }
    CallbackProvider cbp = tracer().getUniversalCallbackProvider();
    RequestContext requestContext = span.getRequestContext();
    if (cbp != null && requestContext != null) {
      BiFunction<RequestContext, IGSpanInfo, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestEnded());
      if (callback != null) {
        callback.apply(requestContext, span);
      }
    }
  }

  private Flow<Void> callIGCallbackAddressAndPort(
      @Nonnull final AgentSpan span,
      final String ip,
      final int port,
      final String inferredClientIp) {
    CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    if (cbp == null || (ip == null && inferredClientIp == null && port == UNSET_PORT)) {
      return Flow.ResultFlow.empty();
    }
    RequestContext ctx = span.getRequestContext();
    if (ctx == null) {
      return Flow.ResultFlow.empty();
    }

    if (inferredClientIp != null) {
      BiFunction<RequestContext, String, Flow<Void>> inferredAddrCallback =
          cbp.getCallback(EVENTS.requestInferredClientAddress());
      if (inferredAddrCallback != null) {
        inferredAddrCallback.apply(ctx, inferredClientIp);
      }
    }

    if (ip != null || port != UNSET_PORT) {
      TriFunction<RequestContext, String, Integer, Flow<Void>> addrCallback =
          cbp.getCallback(EVENTS.requestClientSocketAddress());
      if (addrCallback != null) {
        return addrCallback.apply(ctx, ip != null ? ip : "0.0.0.0", port);
      }
    }
    return Flow.ResultFlow.empty();
  }

  /** This passes the headers through to the InstrumentationGateway */
  protected static final class IGKeyClassifier implements AgentPropagation.KeyClassifier {

    public static IGKeyClassifier create(
        RequestContext requestContext,
        TriConsumer<RequestContext, String, String> headerCallback,
        Function<RequestContext, Flow<Void>> doneCallback) {
      if (null == requestContext || null == headerCallback) {
        return null;
      }
      return new IGKeyClassifier(requestContext, headerCallback, doneCallback);
    }

    private final RequestContext requestContext;
    private final TriConsumer<RequestContext, String, String> headerCallback;
    private final Function<RequestContext, Flow<Void>> doneCallback;

    private IGKeyClassifier(
        RequestContext requestContext,
        TriConsumer<RequestContext, String, String> headerCallback,
        Function<RequestContext, Flow<Void>> doneCallback) {
      this.requestContext = requestContext;
      this.headerCallback = headerCallback;
      this.doneCallback = doneCallback;
    }

    @Override
    public boolean accept(String key, String value) {
      headerCallback.accept(requestContext, key, value);
      return true;
    }

    public Flow<Void> done() {
      if (null != doneCallback) {
        return doneCallback.apply(requestContext);
      }
      return Flow.ResultFlow.empty();
    }
  }

  private static final class ResponseHeaderTagClassifier implements AgentPropagation.KeyClassifier {
    static ResponseHeaderTagClassifier create(AgentSpan span, Map<String, String> headerTags) {
      if (span == null || headerTags == null || headerTags.isEmpty()) {
        return null;
      }
      return new ResponseHeaderTagClassifier(span, headerTags);
    }

    private final AgentSpan span;
    private final Map<String, String> headerTags;
    private final String wildcardHeaderPrefix;

    public ResponseHeaderTagClassifier(AgentSpan span, Map<String, String> headerTags) {
      this.span = span;
      this.headerTags = headerTags;
      this.wildcardHeaderPrefix = this.headerTags.get("*");
    }

    @Override
    public boolean accept(String key, String value) {
      if (wildcardHeaderPrefix != null) {
        span.setTag((wildcardHeaderPrefix + key).toLowerCase(Locale.ROOT), value);
      }
      String mappedKey = headerTags.get(key.toLowerCase(Locale.ROOT));
      if (mappedKey != null) {
        span.setTag(mappedKey, value);
      }
      return true;
    }
  }
}
