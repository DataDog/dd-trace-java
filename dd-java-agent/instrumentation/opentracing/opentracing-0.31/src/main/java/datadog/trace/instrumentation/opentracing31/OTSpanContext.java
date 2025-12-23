package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import io.opentracing.SpanContext;
import java.util.Map;

class OTSpanContext implements SpanContext {
  private final AgentSpanContext delegate;

  OTSpanContext(final AgentSpanContext delegate) {
    this.delegate = delegate;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return delegate.baggageItems();
  }

  AgentSpanContext getDelegate() {
    return delegate;
  }
}
