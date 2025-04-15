package datadog.trace.core.tagprocessor;

import static datadog.trace.api.DDTags.PROCESS_TAGS;

import datadog.trace.api.ProcessTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.Map;

public class ProcessTagsAdder implements TagsPostProcessor {
  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    final CharSequence processTags = ProcessTags.getTagsForSerialization();
    if (processTags != null) {
      unsafeTags.put(PROCESS_TAGS, processTags);
    }
    return unsafeTags;
  }
}
