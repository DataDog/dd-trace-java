package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import java.math.BigDecimal;
import java.math.BigInteger;

/** A numeric {@linkplain com.datadog.debugger.el.Value} */
public final class NumericValue extends Literal<Number> {
  public NumericValue(Number value) {
    super(widen(value));
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

  @Override
  public String toString() {
    return "NumericLiteral{" + "value=" + value + '}';
  }

  @Override
  public String prettyPrint() {
    if (value instanceof Double) {
      return String.valueOf(value.doubleValue());
    }
    if (value instanceof Long) {
      return String.valueOf(value.longValue());
    }
    if (value instanceof BigDecimal || value instanceof BigInteger) {
      return value.toString();
    }
    return "null";
  }
}
