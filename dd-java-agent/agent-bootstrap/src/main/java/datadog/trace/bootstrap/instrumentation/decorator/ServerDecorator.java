package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class ServerDecorator extends BaseDecorator {
  private static final TagMap.Entry SPAN_KIND_ENTRY =
      TagMap.Entry.create(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
  private static final TagMap.Entry LANG_ENTRY =
      TagMap.Entry.create(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE);

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(SPAN_KIND_ENTRY);
    span.setTag(LANG_ENTRY);

    return super.afterStart(span);
  }
}
