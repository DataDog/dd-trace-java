package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.Visitor;

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
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
