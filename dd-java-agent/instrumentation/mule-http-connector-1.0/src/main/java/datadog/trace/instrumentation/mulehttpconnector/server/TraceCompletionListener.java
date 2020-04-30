package datadog.trace.instrumentation.mulehttpconnector.server;

import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.RESPONSE;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.SPAN;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class TraceCompletionListener implements FilterChainContext.CompletionListener {

  private final AgentSpan span;

  public TraceCompletionListener(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onComplete(final FilterChainContext filterChainContext) {
    DECORATE.beforeFinish(span);
    span.finish();
    filterChainContext.getAttributes().removeAttribute(SPAN);
    filterChainContext.getAttributes().removeAttribute(RESPONSE);
  }
}
