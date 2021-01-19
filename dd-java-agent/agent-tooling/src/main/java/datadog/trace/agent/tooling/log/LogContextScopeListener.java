package datadog.trace.agent.tooling.log;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDId;
import datadog.trace.api.Tracer;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.context.ScopeListener;

/**
 * A scope listener that receives the MDC/ThreadContext put and receive methods and update the trace
 * and span reference anytime a new scope is activated or closed.
 */
public abstract class LogContextScopeListener implements ScopeListener, WithGlobalTracer.Callback {

  @Override
  public void afterScopeActivated(DDId traceId, DDId spanId) {
    add(CorrelationIdentifier.getTraceIdKey(), traceId);
    add(CorrelationIdentifier.getSpanIdKey(), spanId);
  }

  @Override
  public void afterScopeClosed() {
    remove(CorrelationIdentifier.getTraceIdKey());
    remove(CorrelationIdentifier.getSpanIdKey());
  }

  public abstract void add(String key, String value);

  public abstract void remove(String key);

  @Override
  public void withTracer(Tracer tracer) {
    tracer.addScopeListener(this);
  }
}
