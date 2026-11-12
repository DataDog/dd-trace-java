package datadog.trace.instrumentation.azure.functions.worker;

import com.microsoft.azure.functions.TraceContext;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public final class TraceContextExtractAdapter
    implements AgentPropagation.ContextVisitor<TraceContext> {
  public static final TraceContextExtractAdapter GETTER = new TraceContextExtractAdapter();

  private static final String TRACEPARENT = "traceparent";
  private static final String TRACESTATE = "tracestate";

  private TraceContextExtractAdapter() {}

  @Override
  public void forEachKey(TraceContext carrier, AgentPropagation.KeyClassifier classifier) {
    final String traceparent = carrier.getTraceparent();
    if (traceparent != null && !classifier.accept(TRACEPARENT, traceparent)) {
      return;
    }
    final String tracestate = carrier.getTracestate();
    if (tracestate != null) {
      classifier.accept(TRACESTATE, tracestate);
    }
  }
}
