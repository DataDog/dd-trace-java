package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.Values;

/** A value represention {@literal null} */
public final class NullValue extends Literal<Object> {
  public static final NullValue INSTANCE = new NullValue();

  private NullValue() {
    super(Values.NULL_OBJECT);
  }

  @SuppressWarnings("unchecked")
  public static <T> Value<T> instance() {
    return (Value<T>) INSTANCE;
  }

  @Override
  public boolean isUndefined() {
    return false;
  }

  @Override
  public boolean isNull() {
    return true;
  }
}
