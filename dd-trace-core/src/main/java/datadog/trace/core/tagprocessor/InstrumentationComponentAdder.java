package datadog.trace.core.tagprocessor;

import static datadog.trace.api.DDTags.INTEGRATION_COMPONENT;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.Map;

public class InstrumentationComponentAdder implements TagsPostProcessor {

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    final CharSequence internalComponentName = spanContext.getInstrumentationComponentName();
    if (internalComponentName != null) {
      unsafeTags.put(INTEGRATION_COMPONENT, internalComponentName);
    } else {
      unsafeTags.remove(INTEGRATION_COMPONENT);
    }
    return unsafeTags;
  }
}
