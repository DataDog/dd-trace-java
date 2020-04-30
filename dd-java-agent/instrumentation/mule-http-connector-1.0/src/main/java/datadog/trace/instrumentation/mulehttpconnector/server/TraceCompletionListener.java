package datadog.trace.instrumentation.mulehttpconnector.server;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.RESPONSE;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpResponsePacket;

public class TraceCompletionListener implements FilterChainContext.CompletionListener {

  private final AgentSpan span;

  public TraceCompletionListener(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onComplete(final FilterChainContext filterChainContext) {
    HttpResponsePacket response =
        (HttpResponsePacket) filterChainContext.getAttributes().getAttribute(RESPONSE);
    if (null != response) {
      // will have been removed and treated separately if an exception was encountered
      DECORATE.onResponse(
          span, (HttpResponsePacket) filterChainContext.getAttributes().getAttribute(RESPONSE));
      DECORATE.beforeFinish(span);
      span.finish();
      filterChainContext.getAttributes().removeAttribute(DD_SPAN_ATTRIBUTE);
      filterChainContext.getAttributes().removeAttribute(RESPONSE);
    }
  }
}
