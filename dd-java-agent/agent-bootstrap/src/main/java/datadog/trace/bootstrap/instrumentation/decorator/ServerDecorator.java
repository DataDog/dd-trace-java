package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class ServerDecorator extends BaseDecorator {

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.setTag(Config.LANGUAGE_TAG_KEY, Config.LANGUAGE_TAG_VALUE);
    span.setTag("_dd.measured", "1");
    return super.afterStart(span);
  }
}
