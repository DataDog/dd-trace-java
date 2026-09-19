package com.datadog.featureflag.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable evaluation metadata, independent of the public OpenFeature SDK. */
public final class EvaluationMetadata {
  private final Map<String, Object> values;

  private EvaluationMetadata(final Map<String, Object> values) {
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public Map<String, Object> values() {
    return values;
  }

  public static EvaluationMetadataBuilder builder() {
    return new EvaluationMetadataBuilder();
  }

  public static final class EvaluationMetadataBuilder {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public EvaluationMetadataBuilder addString(String key, String value) {
      values.put(key, value);
      return this;
    }

    public EvaluationMetadataBuilder addBoolean(String key, boolean value) {
      values.put(key, value);
      return this;
    }

    public EvaluationMetadataBuilder addInteger(String key, int value) {
      values.put(key, value);
      return this;
    }

    public EvaluationMetadataBuilder addLong(String key, long value) {
      values.put(key, value);
      return this;
    }

    public EvaluationMetadata build() {
      return new EvaluationMetadata(values);
    }
  }
}
