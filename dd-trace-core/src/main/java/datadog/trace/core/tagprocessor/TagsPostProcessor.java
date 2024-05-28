package datadog.trace.core.tagprocessor;

import datadog.trace.core.DDSpanContext;
import java.util.Map;

public interface TagsPostProcessor {
  Map<String, Object> processTags(Map<String, Object> unsafeTags, DDSpanContext spanContext);
}
