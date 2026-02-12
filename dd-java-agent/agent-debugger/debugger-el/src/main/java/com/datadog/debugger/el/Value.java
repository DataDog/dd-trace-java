package com.datadog.debugger.el;

import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NullValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.SetValue;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.el.values.UndefinedValue;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.el.Values;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Represents any value of the expression language */
public interface Value<T> {
  /** A generic undefined value */
  static Value<?> undefinedValue() {
    return UndefinedValue.INSTANCE;
  }

  static Value<?> nullValue() {
    return NullValue.INSTANCE;
  }

  static Value<?> thisValue() {
    return ObjectValue.THIS;
  }

  @SuppressWarnings("unchecked")
  static <T> Value<T> undefined() {
    return (Value<T>) undefinedValue();
  }

  T getValue();

  default ValueType getType() {
    return ValueType.OBJECT;
  }

  default boolean isUndefined() {
    return false;
  }

  default boolean isNull() {
    return false;
  }

  static Value<?> of(Object value, ValueType type) {
    if (value == null || value == Values.NULL_OBJECT || value == nullValue()) {
      return nullValue();
    }
    if (value == Values.UNDEFINED_OBJECT || value == undefinedValue()) {
      return undefinedValue();
    }
    if (value == Values.THIS_OBJECT) {
      return thisValue();
    }
    String typeName = value.getClass().getTypeName();
    if (WellKnownClasses.isStringPrimitive(typeName)) {
      Function<Object, String> toString = WellKnownClasses.getSafeToString(typeName);
      if (toString == null) {
        throw new UnsupportedOperationException("Cannot convert value from type: " + typeName);
      }
      value = toString.apply(value);
    }
    if (WellKnownClasses.isLongPrimitive(typeName)) {
      ToLongFunction<Object> longPrimitiveValueFunction =
          WellKnownClasses.getLongPrimitiveValueFunction(typeName);
      value = longPrimitiveValueFunction.applyAsLong(value);
    }
    if (value instanceof Boolean) {
      return new BooleanValue((Boolean) value, type);
    } else if (value instanceof Character) {
      return new StringValue(value.toString());
    } else if (value instanceof Number) {
      return new NumericValue((Number) value, type);
    } else if (value instanceof String) {
      return new StringValue((String) value);
    } else if (value instanceof List) {
      return new ListValue(value);
    } else if (value.getClass().isArray()) {
      return new ListValue(value);
    } else if (value instanceof Map) {
      return new MapValue(value);
    } else if (value instanceof Set) {
      return new SetValue(value);
    } else if (value instanceof Value) {
      return (Value<?>) value;
    } else {
      return new ObjectValue(value);
    }
  }

  static CapturedContext.CapturedValue toCapturedSnapshot(String name, Value<?> value) {
    return CapturedContext.CapturedValue.of(
        name, ValueType.toString(value.getType()), value.getValue());
  }

  static CapturedContext.CapturedValue toCapturedSnapshot(
      String name,
      Value<?> value,
      int maxReferenceDepth,
      int maxCollectionSize,
      int maxCount,
      int maxFieldCount) {
    return CapturedContext.CapturedValue.of(
        name,
        ValueType.toString(value.getType()),
        value.getValue(),
        maxReferenceDepth,
        maxCollectionSize,
        maxCount,
        maxFieldCount);
  }
}
