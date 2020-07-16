package datadog.trace.agent.tooling.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ContextStoreDefHelper {
  public static Map<String, String> getMappings(final Class<?> targetClass) {
    final ContextStoreDef contextStoreDef = targetClass.getAnnotation(ContextStoreDef.class);

    return getMappings(contextStoreDef);
  }

  public static Map<String, String> getMappings(final ContextStoreDef contextStoreDef) {
    if (contextStoreDef == null) {
      return Collections.emptyMap();
    }

    final Map<String, String> mappings = new HashMap<>();
    for (final ContextStoreMapping mapping : contextStoreDef.value()) {
      mappings.put(mapping.keyClass(), mapping.contextClass());
    }

    return mappings;
  }
}
