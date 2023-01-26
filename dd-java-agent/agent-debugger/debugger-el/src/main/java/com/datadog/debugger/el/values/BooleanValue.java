package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;

/** Constant boolean value */
public final class BooleanValue extends Literal<Boolean> {
  public static final BooleanValue TRUE = new BooleanValue(true);
  public static final BooleanValue FALSE = new BooleanValue(false);

  public BooleanValue(Boolean value) {
    super(value);
  }

  @Override
  public String toString() {
    return "BooleanLiteral{" + "value=" + value + '}';
  }

  @Override
  public String prettyPrint() {
    if (value == null) {
      return "null";
    }
    return String.valueOf(value.booleanValue());
  }
}
