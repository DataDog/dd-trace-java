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
import static datadog.trace.instrumentation.mulehttpconnector.server.TraceCompletionListener.LISTENER;

public class ServerAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Object source, @Advice.Argument(0) final FilterChainContext ctx) {
    if (!(ctx.getMessage() instanceof HttpContent)) {
      System.out.println("I am in the HttpServerFilter");
      return;
    }
    final HttpContent httpContent = ctx.getMessage();
    final HttpHeader httpHeader = httpContent.getHttpHeader();
    System.out.println("I am in the Http Server and have an HttpContent");
    if (httpHeader instanceof HttpRequestPacket) {
      System.out.println("I am in the Http Server and have a HttpRequestPacket");
      final HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
      final HttpResponsePacket httpResponse = httpRequest.getResponse();

      //      final Context parentContext = propagate().extract(httpRequest, GETTER);
      final AgentSpan span = startSpan("mule.http.server");

      DECORATE.afterStart(span);
      DECORATE.onConnection(span, httpRequest);
      DECORATE.onRequest(span, httpRequest);
      DECORATE.onResponse(span, httpResponse);

      LISTENER.setSpan(span);
      ctx.addCompletionListener(LISTENER);
    }
  }
}
