package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;

/**
 * An undefined {@linkplain Value}.<br>
 * An undefined value can result from the {@linkplain ValueReferenceResolver} not being able to
 * properly resolve a reference or an expression using {@linkplain Value#UNDEFINED} value in its
 * computation.
 */
public final class UndefinedValue extends Literal<Object> {
  public static final UndefinedValue INSTANCE = new UndefinedValue();

  private UndefinedValue() {
    super(Values.UNDEFINED_OBJECT);
  }

  @SuppressWarnings("unchecked")
  public static <T> Value<T> instance() {
    return (Value<T>) INSTANCE;
  }

  @Override
  public boolean isUndefined() {
    return true;
  }

  @Override
  public boolean isNull() {
    return false;
  }
}
