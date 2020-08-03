package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.DDComponents.GRIZZLY_FILTER_CHAIN_SERVER;
import static datadog.trace.bootstrap.instrumentation.api.DDSpanNames.GRIZZLY_REQUEST;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

public class GrizzlyDecorator
    extends HttpServerDecorator<HttpRequestPacket, HttpRequestPacket, HttpResponsePacket> {

  public static final GrizzlyDecorator DECORATE = new GrizzlyDecorator();

  @Override
  protected String method(final HttpRequestPacket httpRequest) {
    return httpRequest.getMethod().getMethodString();
  }

  @Override
  protected URI url(final HttpRequestPacket httpRequest) throws URISyntaxException {
    return new URI(
        (httpRequest.isSecure() ? "https://" : "http://")
            + httpRequest.serverName()
            + ":"
            + httpRequest.getServerPort()
            + httpRequest.getRequestURI()
            + (httpRequest.getQueryString() != null ? "?" + httpRequest.getQueryString() : ""));
  }

  @Override
  protected String peerHostIP(final HttpRequestPacket httpRequest) {
    return httpRequest.getRemoteAddress();
  }

  @Override
  protected Integer peerPort(final HttpRequestPacket httpRequest) {
    return httpRequest.getRemotePort();
  }

  @Override
  protected Integer status(final HttpResponsePacket httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"grizzly-client", "ning"};
  }

  @Override
  protected String component() {
    return GRIZZLY_FILTER_CHAIN_SERVER;
  }

  public static void onHttpServerFilterPrepareResponseExit(
      final FilterChainContext ctx, final HttpResponsePacket responsePacket) {
    final AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    DECORATE.onResponse(span, responsePacket);
    span.finish();
    ctx.getAttributes().removeAttribute(DD_SPAN_ATTRIBUTE);
    ctx.getAttributes().removeAttribute(DD_RESPONSE_ATTRIBUTE);
  }

  public static void onHttpCodecFilterExit(
      final FilterChainContext ctx,
      final HttpHeader httpHeader,
      final String originType,
      final String originMethod) {
    // only create a span if there isn't another one attached to the current ctx
    // and if the httpHeader has been parsed into a HttpRequestPacket
    if (ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE) != null
        || !(httpHeader instanceof HttpRequestPacket)) {
      return;
    }
    final HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
    final HttpResponsePacket httpResponse = httpRequest.getResponse();
    final AgentSpan span =
        startSpan(GRIZZLY_REQUEST, propagate().extract(httpHeader, ExtractAdapter.GETTER));
    final AgentScope scope = activateSpan(span, originType, originMethod);
    scope.setAsyncPropagation(true);
    DECORATE.afterStart(span);
    ctx.getAttributes().setAttribute(DD_SPAN_ATTRIBUTE, span);
    ctx.getAttributes().setAttribute(DD_RESPONSE_ATTRIBUTE, httpResponse);
    DECORATE.onConnection(span, httpRequest);
    DECORATE.onRequest(span, httpRequest);
    scope.close();
  }

  public static void onFilterChainFail(final FilterChainContext ctx, final Throwable throwable) {
    final AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    if (null != span) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span).finish();
      span.finish();
    }
    ctx.getAttributes().removeAttribute(DD_SPAN_ATTRIBUTE);
    ctx.getAttributes().removeAttribute(DD_RESPONSE_ATTRIBUTE);
  }
}
