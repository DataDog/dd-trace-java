package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

public class GrizzlyDecorator
    extends HttpServerDecorator<HttpRequestPacket, HttpRequestPacket, HttpResponsePacket> {

  public static final CharSequence GRIZZLY_FILTER_CHAIN_SERVER =
      UTF8BytesString.create("grizzly-filterchain-server");

  public static final CharSequence GRIZZLY_REQUEST = UTF8BytesString.create("grizzly.request");

  public static final GrizzlyDecorator DECORATE = new GrizzlyDecorator();

  @Override
  protected String method(final HttpRequestPacket httpRequest) {
    return httpRequest.getMethod().getMethodString();
  }

  @Override
  protected URIDataAdapter url(final HttpRequestPacket httpRequest) {
    return new HTTPRequestPacketURIDataAdapter(httpRequest);
  }

  @Override
  protected String peerHostIP(final HttpRequestPacket httpRequest) {
    return httpRequest.getRemoteAddress();
  }

  @Override
  protected int peerPort(final HttpRequestPacket httpRequest) {
    return httpRequest.getRemotePort();
  }

  @Override
  protected int status(final HttpResponsePacket httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"grizzly-client", "ning"};
  }

  @Override
  protected CharSequence component() {
    return GRIZZLY_FILTER_CHAIN_SERVER;
  }

  public static void onHttpServerFilterPrepareResponseEnter(
      FilterChainContext ctx, HttpResponsePacket responsePacket) {
    AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    span.finishThreadMigration();
    DECORATE.onResponse(span, responsePacket);
  }

  public static void onHttpServerFilterPrepareResponseExit(
      FilterChainContext ctx, HttpResponsePacket responsePacket) {
    AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    span.finish();
    ctx.getAttributes().removeAttribute(DD_SPAN_ATTRIBUTE);
    ctx.getAttributes().removeAttribute(DD_RESPONSE_ATTRIBUTE);
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
    AgentSpan.Context.Extracted context = propagate().extract(httpHeader, ExtractAdapter.GETTER);
    AgentSpan span = startSpan(GRIZZLY_REQUEST, context);
    AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    DECORATE.afterStart(span);
    ctx.getAttributes().setAttribute(DD_SPAN_ATTRIBUTE, span);
    ctx.getAttributes().setAttribute(DD_RESPONSE_ATTRIBUTE, httpResponse);
    DECORATE.onRequest(span, httpRequest, httpRequest, context);
    scope.close();
    span.startThreadMigration();
  }

  public static void onFilterChainFail(FilterChainContext ctx, Throwable throwable) {
    AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    if (null != span) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span).finish();
      span.finish();
    }
    ctx.getAttributes().removeAttribute(DD_SPAN_ATTRIBUTE);
    ctx.getAttributes().removeAttribute(DD_RESPONSE_ATTRIBUTE);
  }
}
