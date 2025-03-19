package datadog.trace.core.tagprocessor;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.Map;

public abstract class TagsPostProcessor {
  /*
   * DQH - For testing purposes only
   */
  @Deprecated
  final Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext context, List<AgentSpanLink> links) {
    TagMap map = TagMap.fromMap(unsafeTags);
    this.processTags(map, context, links);
    return map;
  }

  public abstract void processTags(
      TagMap unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks);
}
