package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

public class ServerAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Object source, @Advice.Argument(0) final FilterChainContext ctx) {
    if (!(ctx.getMessage() instanceof HttpContent)) {
      return;
    }
    final HttpContent httpContent = ctx.getMessage();
    final HttpHeader httpHeader = httpContent.getHttpHeader();
    if (httpHeader instanceof HttpRequestPacket) {
      final HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
      final HttpResponsePacket httpResponse = httpRequest.getResponse();

      final AgentSpan span = startSpan("mule.http.requester.server");
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, httpRequest);
      DECORATE.onRequest(span, httpRequest);
      DECORATE.onResponse(span, httpResponse);
      DECORATE.beforeFinish(span);

      span.finish();
    }
  }
}
