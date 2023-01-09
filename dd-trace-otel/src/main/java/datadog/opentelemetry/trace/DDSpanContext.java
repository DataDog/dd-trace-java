package datadog.opentelemetry.trace;

import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

public class DDSpanContext implements SpanContext {
  private final String traceId;
  private final String spanId;
  private final boolean remote;

  public DDSpanContext(String traceId, String spanId, boolean remote) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.remote = remote;
  }

  public static SpanContext fromLocalSpan(AgentSpan delegate) {
    // TODO Lazily create String ID
    String traceId = delegate.getTraceId().toHexStringPadded(32);
    String spanId = DDSpanId.toHexString(delegate.getSpanId());
    return new DDSpanContext(traceId, spanId, false);
  }

  @Override
  public String getTraceId() {
    return this.traceId;
  }

  @Override
  public String getSpanId() {
    return this.spanId;
  }

  @Override
  public TraceFlags getTraceFlags() {
    // TODO Get the sampling state
    // Otherwise, check the current sampling rules for deferred sampling rules:
    // > If a component deferred or delayed the decision and only a subset of telemetry will be
    // recorded, the sampled flag should be propagated unchanged.
    // > It should be set to 0 as the default option when the trace is initiated by this component.
    // Reference: https://www.w3.org/TR/trace-context/#sampled-flag
    return TraceFlags.getDefault();
  }

  @Override
  public TraceState getTraceState() {
    return TraceState.getDefault(); // TODO
  }

  @Override
  public boolean isRemote() {
    return this.remote;
  }
}
