package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class TypedValue {
  private final Type type;
  private final Object value;
  private final long cpIndex;
  private final Map<String, TypedFieldValue> fields;
  private final boolean isNull;

  public static TypedValue of(Type type, Consumer<FieldValueBuilder> builderCallback) {
    TypedValue checkValue = new TypedValue(type, builderCallback);
    if (type.hasConstantPool()) {
      return type.getConstantPool().addOrGet(checkValue);
    }
    return checkValue;
  }

  public static TypedValue of(Type type, Object value) {
    if (type.hasConstantPool()) {
      return type.getConstantPool().addOrGet(value);
    }
    return new TypedValue(type, value, Long.MIN_VALUE);
  }

  public static TypedValue ofNull(Type type) {
    if (!type.canAccept(null)) {
      throw new IllegalArgumentException();
    }
    return new TypedValue(type, null, Long.MIN_VALUE);
  }

  static TypedValue cpClone(TypedValue src, long cpIndex) {
    return new TypedValue(src.getType(), src.fields, cpIndex);
  }

  @SuppressWarnings("unchecked")
  TypedValue(Type type, Object value, long cpIndex) {
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

  TypedValue(Type type, Consumer<FieldValueBuilder> fieldAccessTarget) {
    this(type, getFieldValues(type, fieldAccessTarget));
  }

  TypedValue(Type type, Map<String, TypedFieldValue> fields) {
    this.type = type;
    this.value = null;
    this.fields = fields;
    this.isNull = fields == null;
    this.cpIndex = Long.MIN_VALUE;
  }

  private static <T> Map<String, TypedFieldValue> getFieldValues(
      Type type, Consumer<FieldValueBuilder> fieldAccessTarget) {
    FieldValueBuilder access = new FieldValueBuilder(type);
    fieldAccessTarget.accept(access);
    return access.getFieldValues();
  }

  public boolean isBuiltin() {
    return type.isBuiltin();
  }

  public Type getType() {
    return type;
  }

  public Object getValue() {
    return value;
  }

  public long getCPIndex() {
    return cpIndex;
  }

  public boolean isNull() {
    return isNull;
  }

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
  public String toString() {
    StringBuilder sb = new StringBuilder("TypedValue{\n");
    sb.append("type=").append(type.getTypeName()).append("(").append(type.getId()).append(")\n");
    if (isNull()) {
      sb.append("NULL\n}");
    } else {
      if (cpIndex > Long.MIN_VALUE) {
        sb.append("cpIndex=").append(cpIndex).append('\n');
      }
      if (value != null) {
        sb.append("value=").append(value).append("\n");
      } else {
        sb.append("fields={\n");
        for (TypedFieldValue fieldValue : getFieldValues()) {
          String name = fieldValue.getField().getName();
          if (fieldValue.isArray()) {
            sb.append(name).append("=[\n");
            for (TypedValue value : fieldValue.getValues()) {
              sb.append(value).append("\n");
            }
            sb.append("]\n");
          } else {
            sb.append(name).append("=").append(fieldValue.getValue()).append("\n");
          }
        }
      }
      sb.append("}\n");
    }
    return sb.toString();
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
