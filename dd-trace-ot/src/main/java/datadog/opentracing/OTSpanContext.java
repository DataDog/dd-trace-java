package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentracing.SpanContext;
import java.util.Map;
import java.util.Objects;

class OTSpanContext implements SpanContext {
  private final AgentSpan.Context delegate;

  OTSpanContext(final AgentSpan.Context delegate) {
    this.delegate = delegate;
  }

  @Override
  public String toTraceId() {
    return delegate.getTraceId().toString();
  }

  @Override
  public String toSpanId() {
    return delegate.getSpanId().toString();
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return delegate.baggageItems();
  }

  AgentSpan.Context getDelegate() {
    return delegate;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final OTSpanContext that = (OTSpanContext) o;
    return delegate.equals(that.delegate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(delegate);
  }
}
