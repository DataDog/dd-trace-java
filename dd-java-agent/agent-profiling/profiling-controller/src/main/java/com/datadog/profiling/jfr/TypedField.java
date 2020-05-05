package com.datadog.profiling.jfr;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A representation of a typed field with a name */
public final class TypedField {
  private final String name;
  private final JFRType type;
  private final boolean isArray;
  private final List<JFRAnnotation> annotations;

  TypedField(String name, JFRType type) {
    this(name, type, false, Collections.emptyList());
  }

  TypedField(String name, JFRType type, List<JFRAnnotation> annotations) {
    this(name, type, false, annotations);
  }

  TypedField(String name, JFRType type, boolean isArray) {
    this(name, type, isArray, Collections.emptyList());
  }

  TypedField(String name, JFRType type, boolean isArray, List<JFRAnnotation> annotations) {
    this.name = name;
    this.type = type;
    this.isArray = isArray;
    this.annotations = Collections.unmodifiableList(annotations);
  }

  /** @return field name */
  public String getName() {
    return name;
  }

  /** @return field type */
  public JFRType getType() {
    return type;
  }

  /** @return is the field content an array */
  public boolean isArray() {
    return isArray;
  }

  public List<JFRAnnotation> getAnnotations() {
    return annotations;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TypedField that = (TypedField) o;
    return isArray == that.isArray
        && name.equals(that.name)
        && type.equals(that.type)
        && annotations.equals(that.annotations);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, type, isArray, annotations);
  }
}
