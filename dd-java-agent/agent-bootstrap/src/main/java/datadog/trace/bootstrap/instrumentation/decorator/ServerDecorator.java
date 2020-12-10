package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class ServerDecorator extends BaseDecorator {

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.setTag(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE);
    return super.afterStart(span);
  }
}
