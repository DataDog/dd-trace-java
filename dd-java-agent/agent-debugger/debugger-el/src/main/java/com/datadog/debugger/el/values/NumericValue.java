package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.Visitor;

/** A numeric {@linkplain com.datadog.debugger.el.Value} */
public final class NumericValue extends Literal<Number> {
  public NumericValue(Number value, ValueType type) {
    super(value, type);
  }

  private static Number widen(Number value) {
    if (value instanceof Integer || value instanceof Byte || value instanceof Short) {
      return value.longValue();
    }
    if (value instanceof Float) {
      return value.doubleValue();
    }
    return value;
  }

  public Number getWidenValue() {
    return widen(getValue());
  }

  @Override
  public String toString() {
    return "NumericLiteral{" + "value=" + value + '}';
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
