package datadog.trace.core.tagprocessor;

import datadog.trace.api.KnownTagIds;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;

public class ServiceNameSourceAdder extends TagsPostProcessor {
  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, AppendableSpanLinks spanLinks) {
    final CharSequence serviceNameSource = spanContext.getServiceNameSource();
    if (serviceNameSource != null) {
      unsafeTags.set(KnownTagIds.SVC_SRC_ID, serviceNameSource);
    } else {
      unsafeTags.remove(KnownTagIds.SVC_SRC_ID);
    }
  }
}
