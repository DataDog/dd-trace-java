package datadog.trace.core.tagprocessor;

import java.util.Map;

public interface TagsPostProcessor {
  Map<String, Object> processTags(Map<String, Object> unsafeTags);
}
