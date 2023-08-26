package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.util.Map;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpCodecFilter;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.HttpServerFilter;

public class GrizzlyDecorator
    extends HttpServerDecorator<
        HttpRequestPacket, HttpRequestPacket, HttpResponsePacket, HttpRequestPacket> {

  public static final CharSequence GRIZZLY_FILTER_CHAIN_SERVER =
      UTF8BytesString.create("grizzly-filterchain-server");

  public static final CharSequence GRIZZLY_REQUEST = UTF8BytesString.create("grizzly.request");

  public static final GrizzlyDecorator DECORATE = new GrizzlyDecorator();

  @Override
  protected AgentPropagation.ContextVisitor<HttpRequestPacket> getter() {
    return ExtractAdapter.requestGetter();
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponsePacket> responseGetter() {
    return ExtractAdapter.responseGetter();
  }

  @Override
  public CharSequence spanName() {
    return GRIZZLY_REQUEST;
  }

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
    if (null != span) {
      DECORATE.onResponse(span, responsePacket);
    }
  }

  public static void onHttpServerFilterPrepareResponseExit(
      FilterChainContext ctx, HttpResponsePacket responsePacket) {
    AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
    if (null != span) {
      DECORATE.beforeFinish(span);
      span.finish();
    }
    ctx.getAttributes().removeAttribute(DD_SPAN_ATTRIBUTE);
    ctx.getAttributes().removeAttribute(DD_RESPONSE_ATTRIBUTE);
  }

  public static NextAction onHttpCodecFilterExit(
      FilterChainContext ctx, HttpHeader httpHeader, HttpCodecFilter thiz, NextAction nextAction) {
    // only create a span if there isn't another one attached to the current ctx
    // and if the httpHeader has been parsed into a HttpRequestPacket
    if (ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE) != null
        || !(httpHeader instanceof HttpRequestPacket)) {
      return nextAction;
    }
    HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
    HttpResponsePacket httpResponse = httpRequest.getResponse();
    AgentSpan.Context.Extracted context = DECORATE.extract(httpRequest);
    AgentSpan span = DECORATE.startSpan(httpRequest, context);
    AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    DECORATE.afterStart(span);
    ctx.getAttributes().setAttribute(DD_SPAN_ATTRIBUTE, span);
    ctx.getAttributes().setAttribute(DD_RESPONSE_ATTRIBUTE, httpResponse);
    DECORATE.onRequest(span, httpRequest, httpRequest, context);

    Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
    if (rba != null && thiz instanceof HttpServerFilter) {
      span.getRequestContext().getTraceSegment().effectivelyBlocked();
      nextAction =
          GrizzlyHttpBlockingHelper.block(
              ctx, (HttpServerFilter) thiz, httpRequest, httpResponse, rba, nextAction);
    }
    if (ActiveSubsystems.APPSEC_ACTIVE) {
      RequestContext requestContext = span.getRequestContext();
      if (requestContext != null) {
        BlockResponseFunction brf = requestContext.getBlockResponseFunction();
        if (brf instanceof GrizzlyHttpBlockResponseFunction) {
          ((GrizzlyHttpBlockResponseFunction) brf).ctx = ctx;
        }
      }
    }
    scope.close();

    return nextAction;
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

  @Override
  protected BlockResponseFunction createBlockResponseFunction(
      HttpRequestPacket httpRequestPacket, HttpRequestPacket httpRequestPacket2) {
    return new GrizzlyHttpBlockResponseFunction(httpRequestPacket.getHeader("Accept"));
  }

  public static class GrizzlyHttpBlockResponseFunction implements BlockResponseFunction {
    private final String acceptHeader;
    public volatile FilterChainContext ctx;

    public GrizzlyHttpBlockResponseFunction(String acceptHeader) {
      this.acceptHeader = acceptHeader;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders) {
      if (ctx == null) {
        return false;
      }
      return GrizzlyHttpBlockingHelper.block(
          ctx, acceptHeader, statusCode, templateType, extraHeaders, segment);
    }
  }
}
