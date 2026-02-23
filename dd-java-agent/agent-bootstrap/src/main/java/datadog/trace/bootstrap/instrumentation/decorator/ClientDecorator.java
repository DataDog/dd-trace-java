package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class ClientDecorator extends BaseDecorator {

  protected abstract String service();

  protected String spanKind() {
    return Tags.SPAN_KIND_CLIENT;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    final String service = service();
    if (service != null) {
      span.setServiceName(service, component());
    }
    span.setTag(Tags.SPAN_KIND, spanKind());

    // Generate metrics for all client spans.
    span.setMeasured(true);
    return super.afterStart(span);
  }
}
