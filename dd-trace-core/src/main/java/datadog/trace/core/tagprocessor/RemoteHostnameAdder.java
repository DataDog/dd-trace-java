package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.core.DDSpanContext;
import java.util.Map;

public class RemoteHostnameAdder implements TagsPostProcessor {
  private final Config config;

  public RemoteHostnameAdder(Config config) {
    this.config = config;
  }

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext) {
    if (spanContext.getSpanId() == spanContext.getRootSpanId()) {
      unsafeTags.put(DDTags.TRACER_HOST, config.getHostName());
    }
    return unsafeTags;
  }
}
