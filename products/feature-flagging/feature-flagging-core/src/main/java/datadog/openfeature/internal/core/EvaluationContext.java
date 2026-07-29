package datadog.openfeature.internal.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenFeature-independent evaluation context. */
public final class EvaluationContext {

  private final String targetingKey;
  private final Map<String, Object> attributes;

  public EvaluationContext(final String targetingKey, final Map<String, Object> attributes) {
    this.targetingKey = targetingKey;
    this.attributes =
        Collections.unmodifiableMap(
            attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes));
  }

  public String targetingKey() {
    return targetingKey;
  }

  public Map<String, Object> attributes() {
    return attributes;
  }

  public Object attribute(final String name) {
    if ("id".equals(name) && !attributes.containsKey(name)) {
      return targetingKey;
    }
    return attributes.get(name);
  }
}
