package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A wrapping type for a typed value. It has an associated {@linkplain JFRType} and a value (or an
 * index to constant-pool for that value).
 */
public final class TypedValue {
  private final JFRType type;
  private final Object value;
  private final long cpIndex;
  private final Map<String, TypedFieldValue> fields;
  private final boolean isNull;

  /**
   * A factory method for properly creating an instance of {@linkplain TypedValue} using the
   * constant pool, if supported.
   *
   * @param type the value type
   * @param builderCallback will be called to initialize the {@linkplain TypedValue} instance lazily
   * @return a {@linkplain TypedValue} instance; either new or retrieved from the constant pool
   */
  public static TypedValue of(JFRType type, Consumer<TypeValueBuilder> builderCallback) {
    TypedValue checkValue = new TypedValue(type, builderCallback);
    if (type.hasConstantPool()) {
      return type.getConstantPool().addOrGet(checkValue);
    }
    return checkValue;
  }

  /**
   * A factory method for properly creating an instance of {@linkplain TypedValue} using the
   * constant pool, if supported.
   *
   * @param type the value type
   * @param value the value
   * @return a {@linkplain TypedValue} instance; either new or retrieved from the constant pool
   */
  public static TypedValue of(JFRType type, Object value) {
    if (type.hasConstantPool()) {
      return type.getConstantPool().addOrGet(value);
    }
    return new TypedValue(type, value, Long.MIN_VALUE);
  }

  /**
   * A factory method for properly creating an instance of {@linkplain TypedValue} holding {@literal
   * null} value
   *
   * @param type the value type
   * @return a null {@linkplain TypedValue} instance
   */
  public static TypedValue ofNull(JFRType type) {
    if (!type.canAccept(null)) {
      throw new IllegalArgumentException();
    }
    return new TypedValue(type, null, Long.MIN_VALUE);
  }

  /**
   * Create a copy of {@linkplain TypedValue} but bound to the given constant pool index
   *
   * @param src the source value
   * @param cpIndex the constant pool index to bind to
   * @return a copy of {@linkplain TypedValue} but bound to the given constant pool index
   * @throws IllegalArgumentException if the source value is a built-in
   */
  static TypedValue withCpIndex(TypedValue src, long cpIndex) {
    if (src.getType().isBuiltin()) {
      throw new IllegalArgumentException();
    }
    return new TypedValue(src.getType(), src.fields, cpIndex);
  }

  @SuppressWarnings("unchecked")
  TypedValue(JFRType type, Object value, long cpIndex) {
    if (!type.canAccept(value)) {
      throw new IllegalArgumentException();
    }
    this.type = type;
    this.value = value instanceof Map ? null : value;
    this.fields =
        value instanceof Map ? (Map<String, TypedFieldValue>) value : Collections.emptyMap();
    this.isNull = value == null;
    this.cpIndex = cpIndex;
  }

  private TypedValue(JFRType type, Consumer<TypeValueBuilder> fieldAccessTarget) {
    this(type, getFieldValues(type, fieldAccessTarget));
  }

  private TypedValue(JFRType type, Map<String, TypedFieldValue> fields) {
    this.type = type;
    this.value = null;
    this.fields = fields;
    this.isNull = fields == null;
    this.cpIndex = Long.MIN_VALUE;
  }

  private static Map<String, TypedFieldValue> getFieldValues(
      JFRType type, Consumer<TypeValueBuilder> fieldAccessTarget) {
    TypeValueBuilderImpl access = new TypeValueBuilderImpl(type);
    fieldAccessTarget.accept(access);
    return access.build();
  }

  /** @return the type */
  public JFRType getType() {
    return type;
  }

  /** @return the wrapped value */
  public Object getValue() {
    return type.isSimple() ? getFieldValues().get(0).getValue().getValue() : value;
  }

  long getCPIndex() {
    return cpIndex;
  }

  /** @return {@literal true} if this holds {@literal null} value */
  public boolean isNull() {
    return isNull;
  }

  /** @return the field values structure */
  public List<TypedFieldValue> getFieldValues() {
    if (isNull) {
      throw new NullPointerException();
    }

    List<TypedFieldValue> values = new ArrayList<>(fields.size());
    for (TypedField field : type.getFields()) {
      TypedFieldValue value = fields.get(field.getName());
      if (value == null) {
        value = new TypedFieldValue(field, TypedValue.ofNull(field.getType()));
      }
      values.add(value);
    }
    return values;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TypedValue that = (TypedValue) o;
    return isNull == that.isNull
        && type.equals(that.type)
        && Objects.equals(value, that.value)
        && Objects.equals(fields, that.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, value, fields, isNull);
  }
}
