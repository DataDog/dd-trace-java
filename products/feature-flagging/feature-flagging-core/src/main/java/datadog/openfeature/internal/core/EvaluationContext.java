package datadog.openfeature.internal.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenFeature-independent evaluation context. */
public final class EvaluationContext {

  private final String targetingKey;
  private final AttributeProvider attributes;

  public EvaluationContext(final String targetingKey, final Map<String, Object> attributes) {
    final Map<String, Object> retained =
        attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes);
    this.targetingKey = targetingKey;
    this.attributes =
        new AttributeProvider() {
          @Override
          public boolean contains(final String name) {
            return retained.containsKey(name);
          }

          @Override
          public Object get(final String name) {
            return retained.get(name);
          }
        };
  }

  private EvaluationContext(final String targetingKey, final AttributeProvider attributeProvider) {
    this.targetingKey = targetingKey;
    this.attributes = attributeProvider;
  }

  public static EvaluationContext lazy(
      final String targetingKey, final AttributeProvider attributeProvider) {
    if (attributeProvider == null) {
      throw new IllegalArgumentException("Attribute provider is required");
    }
    return new EvaluationContext(targetingKey, attributeProvider);
  }

  public String targetingKey() {
    return targetingKey;
  }

  public Object attribute(final String name) {
    if ("id".equals(name) && !attributes.contains(name)) {
      return targetingKey;
    }
    return attributes.get(name);
  }

  public interface AttributeProvider {
    boolean contains(String name);

    Object get(String name);
  }
}
