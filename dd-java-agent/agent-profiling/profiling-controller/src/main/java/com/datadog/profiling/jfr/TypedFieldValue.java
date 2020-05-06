package com.datadog.profiling.jfr;

import java.util.Arrays;

/** The composite of {@linkplain TypedField} and corresponding {@link TypedValue TypedValue(s)} */
public final class TypedFieldValue {
  private final TypedField field;
  private final TypedValue[] values;

  TypedFieldValue(TypedField field, TypedValue value) {
    this(field, new TypedValue[] {value});
  }

  TypedFieldValue(TypedField field, TypedValue[] values) {
    if (values == null) {
      values = new TypedValue[0];
    }
    if (!field.isArray() && values.length > 1) {
      throw new IllegalArgumentException();
    }
    this.field = field;
    this.values = values;
  }

  /** @return the corresponding {@linkplain TypedField} */
  public TypedField getField() {
    return field;
  }

  /**
   * @return the associated value
   * @throws IllegalArgumentException if the field is an array
   */
  public TypedValue getValue() {
    if (field.isArray()) {
      throw new IllegalArgumentException();
    }
    return values[0];
  }

  /**
   * @return the associated values
   * @throws IllegalArgumentException if the field is not an array
   */
  public TypedValue[] getValues() {
    if (!field.isArray()) {
      throw new IllegalArgumentException();
    }
    return Arrays.copyOf(values, values.length);
  }
}
