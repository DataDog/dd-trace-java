package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.Values;

/**
 * A {@linkplain com.datadog.debugger.el.Value} instance wrapping an instance not corresponding to
 * other value types: boolean, string, number, collection, null, undefined
 */
public final class ObjectValue extends Literal<Object> {
  public static final ObjectValue THIS = new ObjectValue(Values.THIS_OBJECT);

  public ObjectValue(Object value) {
    super(value == null ? Values.NULL_OBJECT : value, ValueType.OBJECT);
  }

  @Override
  public String toString() {
    return "ObjectLiteral{" + "value=" + value + '}';
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
