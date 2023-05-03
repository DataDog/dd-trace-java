package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.trace.SpanContext;
import io.opentelemetry.trace.SpanId;
import io.opentelemetry.trace.TraceFlags;
import io.opentelemetry.trace.TraceId;
import io.opentelemetry.trace.TraceState;

public class OtelSpanContext extends SpanContext {
  private static final TraceFlags FLAGS = TraceFlags.builder().setIsSampled(true).build();
  private final AgentSpan.Context delegate;

  OtelSpanContext(final AgentSpan.Context delegate) {
    this.delegate = delegate;
  }

  @Override
  public TraceId getTraceId() {
    return new TraceId(0, delegate.getTraceId().toLong());
  }

  @Override
  public SpanId getSpanId() {
    return new SpanId(delegate.getSpanId());
  }

  @Override
  public TraceFlags getTraceFlags() {
    return FLAGS;
  }

  @Override
  public TraceState getTraceState() {
    return TraceState.getDefault();
  }

  @Override
  public boolean isRemote() {
    // check if delegate is a ExtractedContext
    return false;
  }

  AgentSpan.Context getDelegate() {
    return delegate;
  }
}
