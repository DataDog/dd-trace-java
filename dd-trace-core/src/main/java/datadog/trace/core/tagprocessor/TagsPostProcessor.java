package datadog.trace.core.tagprocessor;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;

public abstract class TagsPostProcessor {
  public abstract void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks);
}
