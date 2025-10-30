package com.datadog.featureflag.exposure;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Objects;

/**
 * The subject uses an {@link AbstractMap} in order to guarantee proper equals and hash code
 * implementation
 */
public class Subject {
  public final String id;
  public final Map<String, Object> attributes;

  public Subject(final String id, final AbstractMap<String, Object> attributes) {
    this.id = id;
    this.attributes = attributes;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final Subject subject = (Subject) o;
    return Objects.equals(id, subject.id) && Objects.equals(attributes, subject.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, attributes);
  }
}
