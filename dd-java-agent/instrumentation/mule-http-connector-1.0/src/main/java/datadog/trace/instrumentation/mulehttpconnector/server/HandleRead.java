package datadog.trace.instrumentation.mulehttpconnector.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.RESPONSE;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.SPAN;
import static datadog.trace.instrumentation.mulehttpconnector.server.ExtractAdapter.GETTER;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

public class HandleRead {

  public static void onExit(FilterChainContext ctx, HttpHeader httpHeader) {
    // only create a span if there isn't another one attached to the current ctx
    // and if the httpHeader has been parsed into a HttpRequestPacket
    if (ctx.getAttributes().getAttribute(SPAN) != null
        || !(httpHeader instanceof HttpRequestPacket)) {
      return;
    }
    HttpRequestPacket httpRequest = (HttpRequestPacket) httpHeader;
    HttpResponsePacket httpResponse = httpRequest.getResponse();
    AgentSpan span = startSpan("http.request", propagate().extract(httpHeader, GETTER));
    AgentScope scope = activateSpan(span).setAsyncPropagation(true);
    DECORATE.afterStart(span);
    ctx.getAttributes().setAttribute(SPAN, span);
    ctx.getAttributes().setAttribute(RESPONSE, httpResponse);
    DECORATE.onConnection(span, httpRequest);
    DECORATE.onRequest(span, httpRequest);
    DECORATE.onResponse(span, httpResponse);
    ctx.addCompletionListener(new TraceCompletionListener(span));
    scope.close();
  }
}
