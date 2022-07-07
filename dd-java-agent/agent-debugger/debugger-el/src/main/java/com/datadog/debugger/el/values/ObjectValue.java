package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import datadog.trace.bootstrap.debugger.el.Values;

/**
 * A {@linkplain com.datadog.debugger.el.Value} instance wrapping an instance not corresponding to
 * other value types: boolean, string, number, collection, null, undefined
 */
public final class ObjectValue extends Literal<Object> {
  public ObjectValue(Object value) {
    super(value == null ? Values.NULL_OBJECT : value);
  }

  @Override
  public String toString() {
    return "ObjectLiteral{" + "value=" + value + '}';
  }
}
