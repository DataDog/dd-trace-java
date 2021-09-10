package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;
import static datadog.trace.api.http.UrlBasedResourceNameCalculator.RESOURCE_NAME_CALCULATOR;
import static datadog.trace.bootstrap.instrumentation.decorator.RouteHandlerDecorator.ROUTE_HANDLER_DECORATOR;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.BitSet;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpServerDecorator<REQUEST, CONNECTION, RESPONSE> extends ServerDecorator {

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

          if (config.isHttpServerTagQueryString()) {
            String query =
                supportsRaw && config.isHttpServerRawQueryString() ? url.rawQuery() : url.query();
            span.setTag(DDTags.HTTP_QUERY, query);
            span.setTag(DDTags.HTTP_FRAGMENT, url.fragment());
          }
          callIGCallbackURI(span, url, method);
          // TODO is this ever false?
          if (SHOULD_SET_URL_RESOURCE_NAME && !span.hasResourceName()) {
            span.setResourceName(RESOURCE_NAME_CALCULATOR.calculate(method, path, encoded));
          }
        } else if (SHOULD_SET_URL_RESOURCE_NAME && !span.hasResourceName()) {
          span.setResourceName(DEFAULT_RESOURCE_NAME);
        }
      } catch (final Exception e) {
        log.debug("Error tagging url", e);
      }
    }

    if (connection != null) {
      final String ip = peerHostIP(connection);
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
      if (SHOULD_SET_404_RESOURCE_NAME
          && status == 404
          && !ROUTE_HANDLER_DECORATOR.hasRouteBasedResourceName(span)) {
        span.setResourceName(NOT_FOUND_RESOURCE_NAME);
      }
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

  private static void callIGCallbackURI(
      @Nonnull final AgentSpan span, @Nonnull final URIDataAdapter url, final String method) {
    // TODO:appsec there must be some better way to do this?
    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null || cbp == null) {
      return;
    }

    TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> callback =
        cbp.getCallback(Events.REQUEST_METHOD_URI_RAW);
    if (callback != null) {
      callback.apply(requestContext, method, url);
    }
  }

  @Override
  public AgentSpan beforeFinish(AgentSpan span) {
    onRequestEndForInstrumentationGateway(span);
    return super.beforeFinish(span);
  }

  private static void onRequestEndForInstrumentationGateway(@Nonnull final AgentSpan span) {
    if (span.getLocalRootSpan() != span) {
      return;
    }
    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    RequestContext requestContext = span.getRequestContext();
    if (cbp != null && requestContext != null) {
      BiFunction<RequestContext, IGSpanInfo, Flow<Void>> callback =
          cbp.getCallback(Events.REQUEST_ENDED);
      if (callback != null) {
        callback.apply(requestContext, span);
      }
    }
  }

  private static Flow<Void> callIGCallbackSocketAddress(
      @Nonnull final AgentSpan span, @Nonnull final String ip, final int port) {
    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    if (cbp == null) {
      return Flow.ResultFlow.empty();
    }
    RequestContext ctx = span.getRequestContext();
    if (ctx != null) {
      TriFunction<RequestContext, String, Integer, Flow<Void>> addrCallback =
          cbp.getCallback(Events.REQUEST_CLIENT_SOCKET_ADDRESS);
      if (null != addrCallback) {
        return addrCallback.apply(ctx, ip != null ? ip : "0.0.0.0", port);
      }
    }
    return Flow.ResultFlow.empty();
  }
}
