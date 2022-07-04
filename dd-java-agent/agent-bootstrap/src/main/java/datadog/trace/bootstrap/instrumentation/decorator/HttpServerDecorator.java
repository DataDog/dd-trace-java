package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_PORT;
import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.function.*;
import datadog.trace.api.function.Function;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
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
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpServerDecorator<REQUEST, CONNECTION, RESPONSE, REQUEST_CARRIER>
    extends ServerDecorator {

  private static final Logger log = LoggerFactory.getLogger(HttpServerDecorator.class);

  public static final String DD_SPAN_ATTRIBUTE = "datadog.span";
  public static final String DD_DISPATCH_SPAN_ATTRIBUTE = "datadog.span.dispatch";
  public static final String DD_RESPONSE_ATTRIBUTE = "datadog.response";

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");
  protected static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");
  private static final boolean SHOULD_SET_404_RESOURCE_NAME =
      Config.get().isRuleEnabled("URLAsResourceNameRule")
          && Config.get().isRuleEnabled("Status404Rule")
          && Config.get().isRuleEnabled("Status404Decorator");
  private static final boolean SHOULD_SET_URL_RESOURCE_NAME =
      Config.get().isRuleEnabled("URLAsResourceNameRule");

  private static final BitSet SERVER_ERROR_STATUSES = Config.get().getHttpServerErrorStatuses();

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
    AgentSpan span =
        tracer().startSpan(spanName(), callIGCallbackStart(context), true).setMeasured(true);
    callIGCallbackRequestHeaders(span, carrier);
    return span;
  }

  public AgentSpan onRequest(
      final AgentSpan span,
      final CONNECTION connection,
      final REQUEST request,
      final AgentSpan.Context.Extracted context) {

    if (context != null) {
      String forwarded = context.getForwarded();
      if (forwarded != null) {
        span.setTag(Tags.HTTP_FORWARDED, forwarded);
      }
      String forwardedProto = context.getForwardedProto();
      if (forwardedProto != null) {
        span.setTag(Tags.HTTP_FORWARDED_PROTO, forwardedProto);
      }
      String forwardedHost = context.getForwardedHost();
      if (forwardedHost != null) {
        span.setTag(Tags.HTTP_FORWARDED_HOST, forwardedHost);
      }
      String forwardedIp = context.getForwardedIp();
      if (forwardedIp != null) {
        span.setTag(Tags.HTTP_FORWARDED_IP, forwardedIp);
      }
      String forwardedPort = context.getForwardedPort();
      if (forwardedPort != null) {
        span.setTag(Tags.HTTP_FORWARDED_PORT, forwardedPort);
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
          Config config = Config.get();
          boolean supportsRaw = url.supportsRaw();
          boolean encoded = supportsRaw && config.isHttpServerRawResource();
          String path = encoded ? url.rawPath() : url.path();

          span.setTag(Tags.HTTP_URL, URIUtils.buildURL(url.scheme(), url.host(), url.port(), path));
          if (context != null && context.getForwardedHost() != null) {
            span.setTag(Tags.HTTP_HOSTNAME, context.getForwardedHost());
          } else if (url.host() != null) {
            span.setTag(Tags.HTTP_HOSTNAME, url.host());
          }

          if (config.isHttpServerTagQueryString()) {
            String query =
                supportsRaw && config.isHttpServerRawQueryString() ? url.rawQuery() : url.query();
            span.setTag(DDTags.HTTP_QUERY, query);
            span.setTag(DDTags.HTTP_FRAGMENT, url.fragment());
          }
          callIGCallbackURI(span, url, method);
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

    String ip = null;
    if (connection != null) {
      ip = peerHostIP(connection);
      final int port = peerPort(connection);
      if (ip != null) {
        if (ip.indexOf(':') > 0) {
          span.setTag(Tags.PEER_HOST_IPV6, ip);
        } else {
          span.setTag(Tags.PEER_HOST_IPV4, ip);
        }
      }
      setPeerPort(span, port);
      // TODO: blocking
      callIGCallbackSocketAddress(span, ip, port);
    }

    InetAddress inferredAddress = ClientIpAddressResolver.doResolve(context);
    // As a fallback, if no IP was resolved, the peer IP address should be checked
    // to see if it is public and used as the resolved IP if it is.
    // If no public IP address, then a private IP address should reported as a fall back.
    if (inferredAddress == null && ip != null) {
      inferredAddress = ClientIpAddressResolver.parseIpAddress(ip);
    }
    if (inferredAddress != null) {
      span.setTag(Tags.HTTP_CLIENT_IP, inferredAddress.getHostAddress());
    }

    return span;
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
    CallbackProvider cbp = tracer().instrumentationGateway();
    if (null != cbp) {
      Supplier<Flow<Object>> startedCB = cbp.getCallback(EVENTS.requestStarted());
      if (null != startedCB) {
        Object requestContextData = startedCB.get().getResult();
        if (null != requestContextData) {
          TagContext tagContext = null;
          if (context == null) {
            tagContext = new TagContext();
          } else if (context instanceof TagContext) {
            tagContext = (TagContext) context;
          }
          if (null != tagContext) {
            context = tagContext.withRequestContextData(requestContextData);
          }
        }
      }
    }
    return context;
  }

  private void callIGCallbackRequestHeaders(AgentSpan span, REQUEST_CARRIER carrier) {
    CallbackProvider cbp = tracer().instrumentationGateway();
    RequestContext<Object> requestContext = span.getRequestContext();
    AgentPropagation.ContextVisitor<REQUEST_CARRIER> getter = getter();
    if (requestContext == null || cbp == null || getter == null) {
      return;
    }
    IGKeyClassifier igKeyClassifier =
        IGKeyClassifier.create(
            requestContext,
            cbp.getCallback(EVENTS.requestHeader()),
            cbp.getCallback(EVENTS.requestHeaderDone()));
    if (null != igKeyClassifier) {
      getter.forEachKey(carrier, igKeyClassifier);
      igKeyClassifier.done();
    }
  }

  private void callIGCallbackResponseAndHeaders(AgentSpan span, RESPONSE carrier, int status) {
    CallbackProvider cbp = tracer().instrumentationGateway();
    RequestContext<Object> requestContext = span.getRequestContext();
    if (cbp == null || requestContext == null) {
      return;
    }
    BiFunction<RequestContext<Object>, Integer, Flow<Void>> addrCallback =
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

  private void callIGCallbackURI(
      @Nonnull final AgentSpan span, @Nonnull final URIDataAdapter url, final String method) {
    // TODO:appsec there must be some better way to do this?
    CallbackProvider cbp = tracer().instrumentationGateway();
    RequestContext<Object> requestContext = span.getRequestContext();
    if (requestContext == null || cbp == null) {
      return;
    }

    TriFunction<RequestContext<Object>, String, URIDataAdapter, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestMethodUriRaw());
    if (callback != null) {
      callback.apply(requestContext, method, url);
    }
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
    CallbackProvider cbp = tracer().instrumentationGateway();
    RequestContext<Object> requestContext = span.getRequestContext();
    if (cbp != null && requestContext != null) {
      BiFunction<RequestContext<Object>, IGSpanInfo, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestEnded());
      if (callback != null) {
        callback.apply(requestContext, span);
      }
    }
  }

  private Flow<Void> callIGCallbackSocketAddress(
      @Nonnull final AgentSpan span, @Nonnull final String ip, final int port) {
    CallbackProvider cbp = tracer().instrumentationGateway();
    if (cbp == null || (ip == null && port == UNSET_PORT)) {
      return Flow.ResultFlow.empty();
    }
    RequestContext<Object> ctx = span.getRequestContext();
    if (ctx != null) {
      TriFunction<RequestContext<Object>, String, Integer, Flow<Void>> addrCallback =
          cbp.getCallback(EVENTS.requestClientSocketAddress());
      if (null != addrCallback) {
        return addrCallback.apply(ctx, ip != null ? ip : "0.0.0.0", port);
      }
    }
    return Flow.ResultFlow.empty();
  }

  /** This passes the headers through to the InstrumentationGateway */
  private static final class IGKeyClassifier implements AgentPropagation.KeyClassifier {

    private static IGKeyClassifier create(
        RequestContext<Object> requestContext,
        TriConsumer<RequestContext<Object>, String, String> headerCallback,
        Function<RequestContext<Object>, Flow<Void>> doneCallback) {
      if (null == requestContext || null == headerCallback) {
        return null;
      }
      return new IGKeyClassifier(requestContext, headerCallback, doneCallback);
    }

    private final RequestContext<Object> requestContext;
    private final TriConsumer<RequestContext<Object>, String, String> headerCallback;
    private final Function<RequestContext<Object>, Flow<Void>> doneCallback;

    private IGKeyClassifier(
        RequestContext<Object> requestContext,
        TriConsumer<RequestContext<Object>, String, String> headerCallback,
        Function<RequestContext<Object>, Flow<Void>> doneCallback) {
      this.requestContext = requestContext;
      this.headerCallback = headerCallback;
      this.doneCallback = doneCallback;
    }

    @Override
    public boolean accept(String key, String value) {
      headerCallback.accept(requestContext, key, value);
      return true;
    }

    public void done() {
      if (null != doneCallback) {
        doneCallback.apply(requestContext);
      }
    }
  }

  private static final class ResponseHeaderTagClassifier implements AgentPropagation.KeyClassifier {
    static final ResponseHeaderTagClassifier create(
        AgentSpan span, Map<String, String> headerTags) {
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
