package datadog.trace.agent.tooling.log;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;

/**
 * A scope listener that receives the MDC/ThreadContext put and receive methods and update the trace
 * and span reference anytime a new scope is activated or closed.
 */
public abstract class LogContextScopeListener implements ScopeListener, WithGlobalTracer.Callback {

  @Override
  public void afterScopeActivated() {
    if (AgentTracer.traceConfig().isLogsInjectionEnabled()) {
      add(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
      add(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
    }
  }

  @Override
  public void afterScopeClosed() {
    remove(CorrelationIdentifier.getTraceIdKey());
    remove(CorrelationIdentifier.getSpanIdKey());
  }

  public abstract void add(String key, String value);

  public abstract void remove(String key);

  @Override
  public void withTracer(TracerAPI tracer) {
    tracer.addScopeListener(this);
  }
}
