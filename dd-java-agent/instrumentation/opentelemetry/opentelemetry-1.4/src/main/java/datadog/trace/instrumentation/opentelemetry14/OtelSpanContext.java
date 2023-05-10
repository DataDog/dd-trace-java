package datadog.trace.instrumentation.opentelemetry14;

import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

public class OtelSpanContext implements SpanContext {
  final AgentSpan.Context delegate;
  private final boolean sampled;
  private final boolean remote;
  private String traceId;
  private String spanId;

  public OtelSpanContext(AgentSpan.Context delegate, boolean sampled, boolean remote) {
    this.delegate = delegate;
    this.sampled = sampled;
    this.remote = remote;
  }

  public static SpanContext fromLocalSpan(AgentSpan span) {
    AgentSpan.Context delegate = span.context();
    AgentSpan localRootSpan = span.getLocalRootSpan();
    Integer samplingPriority = localRootSpan.getSamplingPriority();
    boolean sampled = samplingPriority != null && samplingPriority > 0;
    return new OtelSpanContext(delegate, sampled, false);
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
    return TraceState.getDefault();
  }

  @Override
  public boolean isRemote() {
    return this.remote;
  }
}
