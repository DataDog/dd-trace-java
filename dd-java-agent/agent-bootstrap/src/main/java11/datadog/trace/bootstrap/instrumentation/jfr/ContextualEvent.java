package datadog.trace.bootstrap.instrumentation.jfr;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import jdk.jfr.Event;
import jdk.jfr.Label;

public class ContextualEvent extends Event {
  @Label("Local Root Span Id")
  private long localRootSpanId;

  @Label("Span Id")
  private long spanId;

  void setContext(long localRootSpanId, long spanId) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
  }

  public static <T extends ContextualEvent> T captureContext(T event) {
    AgentSpan activeSpan = AgentTracer.activeSpan();
    if (activeSpan != null) {
      long spanId = activeSpan.getSpanId();
      AgentSpan rootSpan = activeSpan.getLocalRootSpan();
      long localRootSpanId = rootSpan == null ? spanId : rootSpan.getSpanId();
      event.setContext(localRootSpanId, spanId);
    }
    return event;
  }
}
