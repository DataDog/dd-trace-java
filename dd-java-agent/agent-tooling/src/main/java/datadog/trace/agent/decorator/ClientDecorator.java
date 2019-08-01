package datadog.trace.agent.decorator;

import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.api.DDTags;

public abstract class ClientDecorator extends BaseDecorator {

  protected abstract String service();

  protected String spanKind() {
    return "client";
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    if (service() != null) {
      span.setMetadata(DDTags.SERVICE_NAME, service());
    }
    span.setMetadata("span.kind", spanKind());
    return super.afterStart(span);
  }
}
