package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentracing.SpanContext;
import java.util.Map;

class OTSpanContext implements SpanContext {
  private final AgentSpan.Context delegate;

  OTSpanContext(final AgentSpan.Context delegate) {
    this.delegate = delegate;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return delegate.baggageItems();
  }

  AgentSpan.Context getDelegate() {
    return delegate;
  }
}
