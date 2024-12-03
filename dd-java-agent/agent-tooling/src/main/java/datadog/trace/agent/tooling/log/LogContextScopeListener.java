package datadog.trace.agent.tooling.log;

import static datadog.trace.api.tracing.ContextKeys.SPAN_CONTEXT_KEY;

import datadog.context.Context;
import datadog.context.ContextListener;
import datadog.context.ContextProvider;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;

/**
 * A scope listener that receives the MDC/ThreadContext put and receive methods and update the trace
 * and span reference anytime a new scope is activated or closed.
 */
public abstract class LogContextScopeListener
    implements ContextListener, WithGlobalTracer.Callback {
  @Override
  public void onAttached(Context previous, Context currentContext) {
    if (AgentTracer.traceConfig().isLogsInjectionEnabled()) {
      AgentSpan span = currentContext.get(SPAN_CONTEXT_KEY);
      if (span == null) {
        remove(CorrelationIdentifier.getTraceIdKey());
        remove(CorrelationIdentifier.getSpanIdKey());
      } else {
        add(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
        add(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
      }
    }
  }

  public abstract void add(String key, String value);

  public abstract void remove(String key);

  @Override
  public void withTracer(TracerAPI tracer) {
    ContextProvider.addListener(this);
  }
}
