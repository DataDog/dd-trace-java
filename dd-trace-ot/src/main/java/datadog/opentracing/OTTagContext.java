package datadog.opentracing;

import datadog.trace.core.propagation.TagContext;
import io.opentracing.SpanContext;
import java.util.Map;
import java.util.Objects;

class OTTagContext implements SpanContext {
  private final TagContext delegate;

  OTTagContext(final TagContext delegate) {
    this.delegate = delegate;
  }

  @Override
  public String toTraceId() {
    return "0";
  }

  @Override
  public String toSpanId() {
    return "0";
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return delegate.baggageItems();
  }

  TagContext getDelegate() {
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
    final OTTagContext that = (OTTagContext) o;
    return delegate.equals(that.delegate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(delegate);
  }
}
