package datadog.trace.instrumentation.mulehttpconnector.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.RESPONSE;
import static datadog.trace.instrumentation.mulehttpconnector.server.ExtractAdapter.GETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

public class ServerDecorator
    extends HttpServerDecorator<HttpRequestPacket, HttpRequestPacket, HttpResponsePacket> {

  public static final ServerDecorator DECORATE = new ServerDecorator();

  @Override
  protected String method(final HttpRequestPacket httpRequest) {
    return httpRequest.getMethod().getMethodString();
  }

  @Override
  protected URI url(final HttpRequestPacket httpRequest) throws URISyntaxException {
    return new URI(
        (httpRequest.isSecure() ? "https://" : "http://")
            + httpRequest.getRemoteHost()
            + ":"
            + httpRequest.getLocalPort()
            + httpRequest.getRequestURI()
            + (httpRequest.getQueryString() != null ? "?" + httpRequest.getQueryString() : ""));
  }

  @Override
  protected String peerHostIP(final HttpRequestPacket httpRequest) {
    return httpRequest.getLocalHost();
  }

  @Override
  protected Integer peerPort(final HttpRequestPacket httpRequest) {
    return httpRequest.getLocalPort();
  }

  @Override
  protected Integer status(final HttpResponsePacket httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"mule-http-connector"};
  }

  @Override
  protected String component() {
    return "grizzly-filterchain-server";
  }

  public static void onHttpCodecFilterExit(FilterChainContext ctx, HttpHeader httpHeader) {
    // only create a span if there isn't another one attached to the current ctx
    // and if the httpHeader has been parsed into a HttpRequestPacket
    if (ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE) != null
        || !(httpHeader instanceof HttpRequestPacket)) {
      return;
    }
    HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
    HttpResponsePacket httpResponse = httpRequest.getResponse();
    AgentSpan span = startSpan("http.request", propagate().extract(httpHeader, GETTER));
    AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    DECORATE.afterStart(span);
    ctx.getAttributes().setAttribute(DD_SPAN_ATTRIBUTE, span);
    ctx.getAttributes().setAttribute(RESPONSE, httpResponse);
    DECORATE.onConnection(span, httpRequest);
    DECORATE.onRequest(span, httpRequest);
    ctx.addCompletionListener(new TraceCompletionListener(span));
    scope.close();
  }

  public static void onFilterChainFail(FilterChainContext ctx, Throwable throwable) {
    AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    if (null != span) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span).finish();
      span.finish();
    }
    ctx.getAttributes().removeAttribute(DD_SPAN_ATTRIBUTE);
    ctx.getAttributes().removeAttribute(RESPONSE);
  }
}
