package datadog.trace.instrumentation.opentelemetry14.trace;

import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

public class OtelSpanContext implements SpanContext {
  final AgentSpan.Context delegate;
  private final boolean sampled;
  private final boolean remote;
  private final TraceState traceState;
  private String traceId;
  private String spanId;

  public OtelSpanContext(
      AgentSpan.Context delegate, boolean sampled, boolean remote, TraceState traceState) {
    this.delegate = delegate;
    this.sampled = sampled;
    this.remote = remote;
    this.traceState = traceState;
  }

  public static SpanContext fromLocalSpan(AgentSpan span) {
    AgentSpan.Context delegate = span.context();
    AgentSpan localRootSpan = span.getLocalRootSpan();
    Integer samplingPriority = localRootSpan.getSamplingPriority();
    boolean sampled = samplingPriority != null && samplingPriority > 0;
    return new OtelSpanContext(delegate, sampled, false, TraceState.getDefault());
  }

  public static SpanContext fromRemote(AgentSpan.Context extracted, TraceState traceState) {
    return new OtelSpanContext(extracted, extracted.getSamplingPriority() > 0, true, traceState);
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
      this.spanId = DDSpanId.toHexStringPadded(this.delegate.getSpanId());
    }
    return this.spanId;
  }

  @Override
  public TraceFlags getTraceFlags() {
    return this.sampled ? TraceFlags.getSampled() : TraceFlags.getDefault();
  }

  @Override
  public TraceState getTraceState() {
    return this.traceState;
  }

  @Override
  public boolean isRemote() {
    return this.remote;
  }

  @Override
  public String toString() {
    return "OtelSpanContext{"
        + "traceId='"
        + getTraceId()
        + "', spanId='"
        + getSpanId()
        + "', sampled="
        + this.sampled
        + ", remote="
        + this.remote
        + '}';
  }
}
