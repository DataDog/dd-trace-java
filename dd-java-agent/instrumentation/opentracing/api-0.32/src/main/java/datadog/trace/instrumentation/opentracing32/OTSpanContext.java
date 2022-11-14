package datadog.trace.instrumentation.opentracing32;

import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentracing.SpanContext;
import java.util.Map;

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
    return DDSpanId.toString(delegate.getSpanId());
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return delegate.baggageItems();
  }

  AgentSpan.Context getDelegate() {
    return delegate;
  }
}
