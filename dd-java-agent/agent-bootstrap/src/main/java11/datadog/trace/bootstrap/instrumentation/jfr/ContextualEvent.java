package datadog.trace.bootstrap.instrumentation.jfr;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public interface ContextualEvent {

  void setContext(long localRootSpanId, long spanId);

  default void captureContext() {
    AgentSpan activeSpan = AgentTracer.activeSpan();
    if (activeSpan != null) {
      long spanId = activeSpan.getSpanId();
      AgentSpan rootSpan = activeSpan.getLocalRootSpan();
      long localRootSpanId = rootSpan == null ? spanId : rootSpan.getSpanId();
      setContext(localRootSpanId, spanId);
    }
  }
}
