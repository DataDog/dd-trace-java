package com.datadog.profiling.jfr;

import java.util.Objects;

/** A representation of a typed field with a name */
public final class TypedField {
  private final String name;
  private final JFRType type;
  private final boolean isArray;

  TypedField(String name, JFRType type) {
    this(name, type, false);
  }

  TypedField(String name, JFRType type, boolean isArray) {
    this.name = name;
    this.type = type;
    this.isArray = isArray;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TypedField typedField = (TypedField) o;
    return Objects.equals(name, typedField.name) && Objects.equals(type, typedField.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, type);
  }
}
