package com.datadog.profiling.jfr;

import java.util.Arrays;
import java.util.Objects;
import lombok.Generated;

/** The composite of {@linkplain TypedField} and corresponding {@link TypedValue TypedValue(s)} */
public final class TypedFieldValue {
  private volatile boolean computeHashCode = true;
  private int hashCode;

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

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TypedFieldValue that = (TypedFieldValue) o;
    return field.equals(that.field) && Arrays.equals(values, that.values);
  }

  @Generated
  @Override
  public int hashCode() {
    if (computeHashCode) {
      int result = Objects.hash(field);
      hashCode = 31 * result + Arrays.hashCode(values);
      computeHashCode = false;
    }
    return hashCode;
  }
}
