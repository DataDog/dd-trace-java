package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.glassfish.grizzly.filterchain.FilterChainContext;

import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

public class TraceCompletionListener implements FilterChainContext.CompletionListener {
  public static final TraceCompletionListener LISTENER = new TraceCompletionListener();
  private AgentSpan span;

  @Override
  public void onComplete(final FilterChainContext filterChainContext) {
    if (span != null) {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  public AgentSpan getSpan() {
    return span;
  }

  public void setSpan(final AgentSpan span) {
    this.span = span;
  }
}
