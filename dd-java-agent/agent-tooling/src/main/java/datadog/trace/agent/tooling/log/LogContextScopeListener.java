package datadog.trace.agent.tooling.log;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;

/**
 * A scope listener that receives the MDC/ThreadContext put and receive methods and update the trace
 * and span reference anytime a new scope is activated or closed.
 */
public abstract class LogContextScopeListener
    implements ExtendedScopeListener, WithGlobalTracer.Callback {

  @Override
  public void afterScopeActivated() {}

  @Override
  public void afterScopeActivated(
      DDTraceId traceId, long localRootSpanId, long spanId, TraceConfig traceConfig) {
    if (traceConfig != null && traceConfig.isLogsInjectionEnabled()) {
      if (InstrumenterConfig.get().isLogs128bTraceIdEnabled() && traceId.toHighOrderLong() != 0) {
        add(CorrelationIdentifier.getTraceIdKey(), traceId.toHexString());
      } else {
        add(CorrelationIdentifier.getTraceIdKey(), traceId.toString());
      }

      add(CorrelationIdentifier.getSpanIdKey(), DDSpanId.toString(spanId));
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
