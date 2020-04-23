package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.RESPONSE;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.SPAN;
import static datadog.trace.instrumentation.mulehttpconnector.server.ExtractAdapter.GETTER;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

public class ServerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(1) final HttpHeader httpHeader) {

    // only create a span if there isn't another one attached to the current ctx
    // and if the httpHeader has been parsed into a HttpRequestPacket
    if (ctx.getAttributes().getAttribute(SPAN) != null
        || !(httpHeader instanceof HttpRequestPacket)) {
      return null;
    }

    final HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
    final HttpResponsePacket httpResponse = httpRequest.getResponse();

    final AgentSpan.Context parentContext = propagate().extract(httpRequest, GETTER);
    final AgentSpan span = startSpan("http.request", parentContext);

    final AgentScope scope = activateSpan(span, false);

    DECORATE.afterStart(span);

    scope.setAsyncPropagation(true);

    ctx.getAttributes().setAttribute(SPAN, span);
    ctx.getAttributes().setAttribute(RESPONSE, httpResponse);

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(1) final HttpHeader httpHeader,
      @Advice.Thrown final Throwable throwable) {

    final AgentSpan span = (AgentSpan) ctx.getAttributes().getAttribute(SPAN);

    if (scope == null || span == null) {
      return;
    }

    if (throwable == null) {
      final HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
      final HttpResponsePacket httpResponse = httpRequest.getResponse();
      // URL is fully parsed at this point
      DECORATE.onConnection(span, httpRequest);
      DECORATE.onRequest(span, httpRequest);
      DECORATE.onResponse(span, httpResponse);
      final TraceCompletionListener traceCompletionListener = new TraceCompletionListener();
      traceCompletionListener.setSpan(span);
      ctx.addCompletionListener(traceCompletionListener);
    } else {
      DECORATE.beforeFinish(span);
      DECORATE.onError(span, throwable);
      span.finish();
    }

    scope.close();
  }
}
