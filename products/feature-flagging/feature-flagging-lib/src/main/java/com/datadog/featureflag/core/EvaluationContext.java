package com.datadog.featureflag.core;

/** Lazy application context; adapters retain their public SDK's value-conversion rules. */
public interface EvaluationContext {
  String getTargetingKey();

  boolean hasAttribute(String name);

  Object attribute(String name);
}
