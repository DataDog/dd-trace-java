package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class ClientDecorator extends BaseDecorator {

  protected abstract String service();

  protected String spanKind() {
    return Tags.SPAN_KIND_CLIENT;
  }

  @Override
  protected SpanPrototype buildSpanPrototype() {
    // Extend the base prototype with the client-level span.kind. spanKind() is overridable and may
    // read a not-yet-initialized static during singleton construction -- building lazily (via
    // spanPrototype()) preserves the ordering safety the old cached spanKindEntry provided.
    return SpanPrototype.builder()
        .extends_(super.buildSpanPrototype())
        .initKind(spanKind())
        .build();
  }

  @Override
  public void afterStart(final AgentSpan span) {
    final String service = service();
    if (service != null) {
      span.setServiceName(service, component());
    }

    // Generate metrics for all client spans.
    span.setMeasured(true);
    super.afterStart(span);
  }
}
