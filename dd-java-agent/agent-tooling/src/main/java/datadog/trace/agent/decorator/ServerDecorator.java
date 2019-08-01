package datadog.trace.agent.decorator;

import datadog.trace.api.Config;
import datadog.trace.instrumentation.api.AgentSpan;

public abstract class ServerDecorator extends BaseDecorator {

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    span.setMetadata("span.kind", "server");
    span.setTag(Config.LANGUAGE_TAG_KEY, Config.LANGUAGE_TAG_VALUE);
    return super.afterStart(span);
  }
}
