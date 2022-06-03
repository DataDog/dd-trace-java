package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Check whether a {@linkplain Value} was resolved as {@linkplain Value#UNDEFINED}.<br>
 * This can happen when the {@linkplain ValueReferenceResolver} is not able to properly resolve a
 * reference or an expression is using {@linkplain Value#UNDEFINED} value in its computation.
 */
public final class IsUndefinedExpression implements PredicateExpression {
  private final ValueExpression<?> valueExpression;

  public IsUndefinedExpression(ValueExpression<?> valueExpression) {
    this.valueExpression = valueExpression;
  }

  @Override
  public Predicate evaluate(ValueReferenceResolver valueRefResolver) {
    if (valueExpression == null) {
      return Predicate.FALSE;
    }
    Value<?> value = valueExpression.evaluate(valueRefResolver);
    return value.isUndefined() ? Predicate.TRUE : Predicate.FALSE;
  }
}
