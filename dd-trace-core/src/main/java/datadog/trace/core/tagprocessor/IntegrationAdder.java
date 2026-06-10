package datadog.trace.core.tagprocessor;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.CoreTagIds;
import datadog.trace.core.DDSpanContext;

public class IntegrationAdder extends TagsPostProcessor {
  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    final CharSequence instrumentationName = spanContext.getIntegrationName();
    if (instrumentationName != null) {
      unsafeTags.set(CoreTagIds.INTEGRATION_ID, instrumentationName);
    } else {
      unsafeTags.remove(CoreTagIds.INTEGRATION_ID);
    }
  }
}
