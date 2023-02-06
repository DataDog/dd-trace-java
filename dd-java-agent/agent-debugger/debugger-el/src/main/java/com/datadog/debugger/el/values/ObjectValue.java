package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import datadog.trace.bootstrap.debugger.el.Values;

/**
 * A {@linkplain com.datadog.debugger.el.Value} instance wrapping an instance not corresponding to
 * other value types: boolean, string, number, collection, null, undefined
 */
public final class ObjectValue extends Literal<Object> {
  public static final ObjectValue THIS = new ObjectValue(Values.THIS_OBJECT);

  public ObjectValue(Object value) {
    super(value == null ? Values.NULL_OBJECT : value);
  }

  @Override
  public String toString() {
    return "ObjectLiteral{" + "value=" + value + '}';
  }

  @Override
  public String prettyPrint() {
    if (value == null || value == Values.NULL_OBJECT) {
      return "null";
    }
    if (value == Values.UNDEFINED_OBJECT) {
      return value.toString();
    }
    if (value == Values.THIS_OBJECT) {
      return "this";
    }
    return value.getClass().getTypeName();
  }
}
