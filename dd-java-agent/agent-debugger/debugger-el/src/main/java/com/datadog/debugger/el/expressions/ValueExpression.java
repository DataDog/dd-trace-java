package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Value;

/**
 * A generic interface for expressions resolving to {@linkplain Value}
 *
 * @param <T>
 */
public interface ValueExpression<T extends Value<?>> extends Expression<T> {
  ValueExpression<?> NULL = context -> Value.nullValue();
  ValueExpression<?> UNDEFINED = context -> Value.undefinedValue();
}
