package datadog.trace.core.tagprocessor;

import static datadog.trace.api.DDTags.DD_INTEGRATION;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;

public class IntegrationAdder extends TagsPostProcessor {
  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    final CharSequence instrumentationName = spanContext.getIntegrationName();
    if (instrumentationName != null) {
      unsafeTags.set(DD_INTEGRATION, instrumentationName);
    } else {
      unsafeTags.remove(DD_INTEGRATION);
    }
  }
}
