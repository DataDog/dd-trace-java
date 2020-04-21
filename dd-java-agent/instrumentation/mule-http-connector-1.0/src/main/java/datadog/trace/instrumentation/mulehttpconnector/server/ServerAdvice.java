package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.server.ExtractAdapter.GETTER;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;
import static datadog.trace.instrumentation.mulehttpconnector.server.TraceCompletionListener.LISTENER;

public class ServerAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.This final Object source,
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Thrown final Throwable throwable) {

    if (!(ctx.getMessage() instanceof HttpContent)) {
      return;
    }

    final HttpContent httpContent = ctx.getMessage();
    final HttpHeader httpHeader = httpContent.getHttpHeader();

    if (httpHeader instanceof HttpRequestPacket) {
      final HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
      final HttpResponsePacket httpResponse = httpRequest.getResponse();

      final AgentSpan.Context parentContext = propagate().extract(httpRequest, GETTER);
      final AgentSpan span = startSpan("http.request", parentContext);

      final AgentScope scope = activateSpan(span, false);
      scope.setAsyncPropagation(true);

      DECORATE.afterStart(span);

      DECORATE.onConnection(span, httpRequest);
      DECORATE.onRequest(span, httpRequest);

      if (throwable == null) {
        DECORATE.onResponse(span, httpResponse);
      } else {
        DECORATE.onError(span, throwable);
        scope.close();
      }

      LISTENER.setSpan(span);
      ctx.addCompletionListener(LISTENER);
    }
  }
}
