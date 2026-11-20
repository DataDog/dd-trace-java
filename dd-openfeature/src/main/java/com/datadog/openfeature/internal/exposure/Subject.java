package com.datadog.openfeature.internal.exposure;

import java.util.Map;

public class Subject {
  public final String id;
  public final Map<String, Object> attributes;

  public Subject(final String id, final Map<String, Object> attributes) {
    this.id = id;
    this.attributes = attributes;
  }
}
