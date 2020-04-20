package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import org.glassfish.grizzly.filterchain.FilterChainContext;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

public class TraceCompletionListener implements FilterChainContext.CompletionListener {
  public static final TraceCompletionListener LISTENER = new TraceCompletionListener();

  private AgentSpan span;

  @Override
  public void onComplete(final FilterChainContext filterChainContext) {
    if (span != null) {
      DECORATE.beforeFinish(span);
      span.finish();
      final TraceScope scope = activeScope();
      if (scope != null) {
        scope.close();
      }
    }
  }

  public AgentSpan getSpan() {
    return span;
  }

  public void setSpan(final AgentSpan span) {
    this.span = span;
  }
}
