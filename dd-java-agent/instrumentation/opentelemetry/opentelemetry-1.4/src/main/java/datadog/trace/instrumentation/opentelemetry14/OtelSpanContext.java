package datadog.trace.instrumentation.opentelemetry14;

import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

public class OtelSpanContext implements SpanContext {
  final AgentSpan.Context delegate;
  private String traceId;
  private String spanId;
  private final boolean remote;

  public OtelSpanContext(AgentSpan.Context delegate, boolean remote) {
    this.delegate = delegate;
    this.remote = remote;
  }

  public static SpanContext fromLocalSpan(AgentSpan span) {
    AgentSpan.Context delegate = span.context();
    return new OtelSpanContext(delegate, false);
  }

  @Override
  public String getTraceId() {
    if (this.traceId == null) {
      this.traceId = this.delegate.getTraceId().toHexString();
    }
    return this.traceId;
  }

  @Override
  public String getSpanId() {
    if (this.spanId == null) {
      this.spanId = DDSpanId.toHexString(this.delegate.getSpanId());
    }
    return this.spanId;
  }

  @Override
  public TraceFlags getTraceFlags() {
    // Get the sampling state.
    // Otherwise, check the current sampling rules for deferred sampling rules:
    // > If a component deferred or delayed the decision and only a subset of telemetry will be
    // recorded, the sampled flag should be propagated unchanged.
    // > It should be set to 0 as the default option when the trace is initiated by this component.
    // Reference: https://www.w3.org/TR/trace-context/#sampled-flag
    return TraceFlags.getDefault();
  }

  @Override
  public TraceState getTraceState() {
    return TraceState.getDefault();
  }

  @Override
  public boolean isRemote() {
    return this.remote;
  }
}
