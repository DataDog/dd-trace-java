package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;

/** A string {@linkplain com.datadog.debugger.el.Value} */
public final class StringValue extends Literal<String> {
  public StringValue(String value) {
    super(value);
  }

  @Override
  public boolean test() {
    return super.test() && !value.isEmpty();
  }

  public boolean isEmpty() {
    return value != null && value.isEmpty();
  }

  public int length() {
    return isNull() ? -1 : value.length();
  }

  @Override
  public boolean isUndefined() {
    return false;
  }

  @Override
  public String toString() {
    return "StringLiteral{" + "value=" + value + '}';
  }
}
