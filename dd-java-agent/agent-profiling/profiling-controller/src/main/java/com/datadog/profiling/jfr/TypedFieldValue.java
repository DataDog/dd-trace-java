package com.datadog.profiling.jfr;

import java.util.Arrays;

public final class TypedFieldValue {
  private final TypedField field;
  private final TypedValue[] values;

  TypedFieldValue(TypedField field, TypedValue value, TypedValue... otherValues) {
    this(field, mergeValues(value, otherValues));
  }

  TypedFieldValue(TypedField field, TypedValue[] values) {
    if (values == null) {
      values = new TypedValue[1];
    }
    if (!field.isArray() && values.length > 1) {
      throw new IllegalArgumentException();
    }
    this.field = field;
    this.values = values;
  }

  private static TypedValue[] mergeValues(TypedValue value, TypedValue... otherValues) {
    TypedValue[] values = new TypedValue[otherValues.length + 1];
    if (otherValues.length > 0) {
      System.arraycopy(otherValues, 0, values, 1, otherValues.length);
    }
    values[0] = value;
    return values;
  }

  public boolean isArray() {
    return field.isArray();
  }

  public boolean isBuiltin() {
    return field.getType().isBuiltin();
  }

  public Type getType() {
    return field.getType();
  }

  public TypedValue getValue() {
    if (isArray()) {
      throw new UnsupportedOperationException();
    }
    return values[0];
  }

  public TypedField getField() {
    return field;
  }

  public TypedValue[] getValues() {
    if (!isArray()) {
      throw new UnsupportedOperationException();
    }
    return Arrays.copyOf(values, values.length);
  }
}
