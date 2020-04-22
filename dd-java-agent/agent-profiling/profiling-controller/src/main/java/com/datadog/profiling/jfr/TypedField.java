package com.datadog.profiling.jfr;

import java.util.Objects;

public final class TypedField {
  private final String name;
  private final Type type;
  private final boolean isArray;

  TypedField(String name, Type type) {
    this(name, type, false);
  }

  TypedField(String name, Type type, boolean isArray) {
    this.name = name;
    this.type = type;
    this.isArray = isArray;
  }

  public String getName() {
    return name;
  }

  public Type getType() {
    return type;
  }

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
