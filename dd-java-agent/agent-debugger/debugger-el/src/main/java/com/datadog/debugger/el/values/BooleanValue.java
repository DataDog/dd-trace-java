package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.Visitor;

/** Constant boolean value */
public final class BooleanValue extends Literal<Boolean> {
  public static final BooleanValue TRUE = new BooleanValue(true, ValueType.BOOLEAN);
  public static final BooleanValue FALSE = new BooleanValue(false, ValueType.BOOLEAN);

  public BooleanValue(Boolean value, ValueType type) {
    super(value, type);
  }

  @Override
  public String toString() {
    return "BooleanLiteral{" + "value=" + value + '}';
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
