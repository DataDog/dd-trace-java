package datadog.trace.core.tagprocessor;

import static datadog.trace.api.DDTags.DD_INTEGRATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.Map;

public class InstrumentationComponentAdder implements TagsPostProcessor {

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    final CharSequence internalComponentName = spanContext.getInstrumentationName();
    if (internalComponentName != null) {
      unsafeTags.put(DD_INTEGRATION, internalComponentName);
    } else {
      unsafeTags.remove(DD_INTEGRATION);
    }
    return unsafeTags;
  }
}
