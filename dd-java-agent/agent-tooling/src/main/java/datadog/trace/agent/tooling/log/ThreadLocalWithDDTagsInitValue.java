package datadog.trace.agent.tooling.log;

import java.util.HashMap;
import java.util.Map;

public class ThreadLocalWithDDTagsInitValue extends ThreadLocal<Map<String, String>> {
  @Override
  protected Map<String, String> initialValue() {
    return new HashMap<>(LogContextScopeListener.LOG_CONTEXT_DD_TAGS);
  }
}
