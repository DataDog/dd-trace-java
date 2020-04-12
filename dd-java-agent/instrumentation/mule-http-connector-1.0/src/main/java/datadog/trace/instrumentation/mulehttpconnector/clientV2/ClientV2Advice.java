package datadog.trace.instrumentation.mulehttpconnector.clientV2;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.clientV2.ClientV2Decorator.DECORATE;

public class ClientV2Advice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.This final Object source, @Advice.Argument(0) final FilterChainContext ctx) {
    if (!(ctx.getMessage() instanceof HttpContent)) {
      return;
    }

    final HttpContent httpContent = ctx.getMessage();
    final HttpHeader httpHeader = httpContent.getHttpHeader();
    if (httpHeader instanceof HttpRequestPacket) {
      final HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
      final HttpResponsePacket httpResponse = httpRequest.getResponse();

      final AgentSpan span = startSpan("mule.http.requester.clientV2");
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, httpRequest);
      DECORATE.onResponse(span, httpResponse);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
