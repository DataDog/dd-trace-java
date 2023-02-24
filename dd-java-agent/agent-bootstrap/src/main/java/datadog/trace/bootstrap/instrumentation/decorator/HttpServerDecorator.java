package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.http.ClientIpAddressResolver;
import java.net.InetAddress;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpServerDecorator<REQUEST, CONNECTION, RESPONSE, REQUEST_CARRIER>
    extends ServerDecorator {

  private static final Logger log = LoggerFactory.getLogger(HttpServerDecorator.class);
  private static final int UNSET_PORT = 0;

  public static final String DD_SPAN_ATTRIBUTE = "datadog.span";
  public static final String DD_DISPATCH_SPAN_ATTRIBUTE = "datadog.span.dispatch";
  public static final String DD_FIN_DISP_LIST_SPAN_ATTRIBUTE =
      "datadog.span.finish_dispatch_listener";
  public static final String DD_RESPONSE_ATTRIBUTE = "datadog.response";

  public static final LinkedHashMap<String, String> SERVER_PATHWAY_EDGE_TAGS;

  static {
    SERVER_PATHWAY_EDGE_TAGS = new LinkedHashMap<>(2);
    // TODO: Refactor TagsProcessor to move it into a package that we can link the constants for.
    SERVER_PATHWAY_EDGE_TAGS.put("direction", "in");
    SERVER_PATHWAY_EDGE_TAGS.put("type", "http");
  }

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");
  protected static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");
  private static final boolean SHOULD_SET_404_RESOURCE_NAME =
      Config.get().isRuleEnabled("URLAsResourceNameRule")
          && Config.get().isRuleEnabled("Status404Rule")
          && Config.get().isRuleEnabled("Status404Decorator");
  private static final boolean SHOULD_SET_URL_RESOURCE_NAME =
      Config.get().isRuleEnabled("URLAsResourceNameRule");

  private static final BitSet SERVER_ERROR_STATUSES = Config.get().getHttpServerErrorStatuses();

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

  public AgentSpan.Context.Extracted extract(REQUEST_CARRIER carrier) {
    AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter = getter();
    if (null == carrier || null == getter) {
      return null;
    }
    return tracer().propagate().extract(carrier, getter);
  }

  public AgentSpan startSpan(REQUEST_CARRIER carrier, AgentSpan.Context.Extracted context) {
    AgentSpan span = tracer().startSpan(spanName(), callIGCallbackStart(context)).setMeasured(true);
    Flow<Void> flow = callIGCallbackRequestHeaders(span, carrier);
    if (flow.getAction() instanceof Flow.Action.RequestBlockingAction) {
      span.setRequestBlockingAction((Flow.Action.RequestBlockingAction) flow.getAction());
    }
    AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter = getter();
    if (null != carrier && null != getter) {
      PathwayContext pathwayContext = propagate().extractPathwayContext(carrier, getter);
      span.mergePathwayContext(pathwayContext);
      tracer().setDataStreamCheckpoint(span, SERVER_PATHWAY_EDGE_TAGS);
    }
    return span;
  }

  public AgentSpan onRequest(
      final AgentSpan span,
      final CONNECTION connection,
      final REQUEST request,
      final AgentSpan.Context.Extracted context) {
    Config config = Config.get();
    boolean clientIpResolverEnabled =
        config.isClientIpEnabled()
            || traceClientIpResolverEnabled && ActiveSubsystems.APPSEC_ACTIVE;

    if (ActiveSubsystems.APPSEC_ACTIVE) {
      RequestContext requestContext = span.getRequestContext();
      if (requestContext != null) {
        BlockResponseFunction brf = createBlockResponseFunction(request, connection);
        if (brf != null) {
          requestContext.setBlockResponseFunction(brf);
        }
      }
    }

    if (context != null) {
      if (clientIpResolverEnabled) {
        String forwarded = context.getForwarded();
        if (forwarded != null) {
          span.setTag(Tags.HTTP_FORWARDED, forwarded);
        }
        String forwardedProto = context.getXForwardedProto();
        if (forwardedProto != null) {
          span.setTag(Tags.HTTP_FORWARDED_PROTO, forwardedProto);
        }
        String forwardedHost = context.getXForwardedHost();
        if (forwardedHost != null) {
          span.setTag(Tags.HTTP_FORWARDED_HOST, forwardedHost);
        }
        String forwardedIp = context.getXForwardedFor();
        if (forwardedIp != null) {
          span.setTag(Tags.HTTP_FORWARDED_IP, forwardedIp);
        }
        String forwardedPort = context.getXForwardedPort();
        if (forwardedPort != null) {
          span.setTag(Tags.HTTP_FORWARDED_PORT, forwardedPort);
        }
      }
      String userAgent = context.getUserAgent();
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
          String path = encoded ? url.rawPath() : url.path();

          span.setTag(Tags.HTTP_URL, URIUtils.buildURL(url.scheme(), url.host(), url.port(), path));
          if (context != null && context.getXForwardedHost() != null) {
            span.setTag(Tags.HTTP_HOSTNAME, context.getXForwardedHost());
          } else if (url.host() != null) {
            span.setTag(Tags.HTTP_HOSTNAME, url.host());
          }

          if (config.isHttpServerTagQueryString()) {
            String query =
                supportsRaw && config.isHttpServerRawQueryString() ? url.rawQuery() : url.query();
            span.setTag(DDTags.HTTP_QUERY, query);
            span.setTag(DDTags.HTTP_FRAGMENT, url.fragment());
          }
          Flow<Void> flow = callIGCallbackURI(span, url, method);
          if (flow.getAction() instanceof Flow.Action.RequestBlockingAction) {
            span.setRequestBlockingAction((Flow.Action.RequestBlockingAction) flow.getAction());
          }
          if (SHOULD_SET_URL_RESOURCE_NAME) {
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
    if (clientIpResolverEnabled) {
      InetAddress inferredAddress = ClientIpAddressResolver.resolve(context, span);
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
    if (flow.getAction() instanceof Flow.Action.RequestBlockingAction) {
      span.setRequestBlockingAction((Flow.Action.RequestBlockingAction) flow.getAction());
    }

    return span;
  }

  protected BlockResponseFunction createBlockResponseFunction(
      REQUEST request, CONNECTION connection) {
    return null;
  }

  public AgentSpan onResponse(final AgentSpan span, final RESPONSE response) {
    if (response != null) {
      final int status = status(response);
      if (status > UNSET_STATUS) {
        span.setHttpStatusCode(status);
      }
      if (SERVER_ERROR_STATUSES.get(status)) {
        span.setError(true);
      }
      if (SHOULD_SET_404_RESOURCE_NAME && status == 404) {
        span.setResourceName(NOT_FOUND_RESOURCE_NAME, ResourceNamePriorities.HTTP_404);
      }

      AgentPropagation.ContextVisitor<RESPONSE> getter = responseGetter();
      if (getter != null) {
        ResponseHeaderTagClassifier tagger =
            ResponseHeaderTagClassifier.create(span, Config.get().getResponseHeaderTags());
        if (tagger != null) {
          getter.forEachKey(response, tagger);
        }
      }

      callIGCallbackResponseAndHeaders(span, response, status);
    }
    return span;
  }

  //  @Override
  //  public Span onError(final Span span, final Throwable throwable) {
  //    assert span != null;
  //    // FIXME
  //    final Object status = span.getTag("http.status");
  //    if (status == null || status.equals(200)) {
  //      // Ensure status set correctly
  //      span.setTag("http.status", 500);
  //    }
  //    return super.onError(span, throwable);
  //  }

  private AgentSpan.Context.Extracted callIGCallbackStart(AgentSpan.Context.Extracted context) {
    AgentTracer.TracerAPI tracer = tracer();
    Supplier<Flow<Object>> startedCbAppSec =
        tracer.getCallbackProvider(RequestContextSlot.APPSEC).getCallback(EVENTS.requestStarted());
    Supplier<Flow<Object>> startedCbIast =
        tracer.getCallbackProvider(RequestContextSlot.IAST).getCallback(EVENTS.requestStarted());

    if (startedCbAppSec == null && startedCbIast == null) {
      return context;
    }

    TagContext tagContext = null;
    if (context == null) {
      tagContext = new TagContext();
    } else if (context instanceof TagContext) {
      tagContext = (TagContext) context;
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

    return context;
  }

  private Flow<Void> callIGCallbackRequestHeaders(AgentSpan span, REQUEST_CARRIER carrier) {
    CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    RequestContext requestContext = span.getRequestContext();
    AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter = getter();
    if (requestContext == null || cbp == null || getter == null) {
      return Flow.ResultFlow.empty();
    }
    IGKeyClassifier igKeyClassifier =
        IGKeyClassifier.create(
            requestContext,
            cbp.getCallback(EVENTS.requestHeader()),
            cbp.getCallback(EVENTS.requestHeaderDone()));
    if (null != igKeyClassifier) {
      getter.forEachKey(carrier, igKeyClassifier);
      return igKeyClassifier.done();
    }
    return Flow.ResultFlow.empty();
  }

  private void callIGCallbackResponseAndHeaders(AgentSpan span, RESPONSE carrier, int status) {
    CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    RequestContext requestContext = span.getRequestContext();
    if (cbp == null || requestContext == null) {
      return;
    }
    BiFunction<RequestContext, Integer, Flow<Void>> addrCallback =
        cbp.getCallback(EVENTS.responseStarted());
    if (null != addrCallback) {
      addrCallback.apply(requestContext, status);
    }
    AgentPropagation.ContextVisitor<RESPONSE> getter = responseGetter();
    if (getter == null) {
      return;
    }
    IGKeyClassifier igKeyClassifier =
        IGKeyClassifier.create(
            requestContext,
            cbp.getCallback(EVENTS.responseHeader()),
            cbp.getCallback(EVENTS.responseHeaderDone()));
    if (null != igKeyClassifier) {
      getter.forEachKey(carrier, igKeyClassifier);
      igKeyClassifier.done();
    }
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
  private static final class IGKeyClassifier implements AgentPropagation.KeyClassifier {

    private static IGKeyClassifier create(
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

    public ResponseHeaderTagClassifier(AgentSpan span, Map<String, String> headerTags) {
      this.span = span;
      this.headerTags = headerTags;
    }

    @Override
    public boolean accept(String key, String value) {
      String mappedKey = headerTags.get(key.toLowerCase());
      if (mappedKey != null) {
        span.setTag(mappedKey, value);
      }
      return true;
    }
  }
}
