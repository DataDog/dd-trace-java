package datadog.trace.core.tagprocessor;

import static datadog.trace.api.DDTags.DD_SVC_SRC;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;

public class ServiceNameSourceAdder extends TagsPostProcessor {
  @Override
  public void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    final CharSequence serviceNameSource = spanContext.getServiceNameSource();
    if (serviceNameSource != null) {
      unsafeTags.set(DD_SVC_SRC, serviceNameSource);
    } else {
      unsafeTags.remove(DD_SVC_SRC);
    }
  }
}
