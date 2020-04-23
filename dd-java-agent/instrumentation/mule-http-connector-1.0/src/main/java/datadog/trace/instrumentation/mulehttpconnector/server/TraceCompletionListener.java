package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.glassfish.grizzly.filterchain.FilterChainContext;

import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.RESPONSE;
import static datadog.trace.instrumentation.mulehttpconnector.ContextAttributes.SPAN;
import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.DECORATE;

public class TraceCompletionListener implements FilterChainContext.CompletionListener {

  private AgentSpan span;

  @Override
  public void onComplete(final FilterChainContext filterChainContext) {
    if (span != null) {
      DECORATE.beforeFinish(span);
      span.finish();
      filterChainContext.getAttributes().setAttribute(SPAN, null);
      filterChainContext.getAttributes().setAttribute(RESPONSE, null);
      setSpan(null);
    }
  }

  public AgentSpan getSpan() {
    return span;
  }

  public void setSpan(final AgentSpan span) {
    this.span = span;
  }
}
