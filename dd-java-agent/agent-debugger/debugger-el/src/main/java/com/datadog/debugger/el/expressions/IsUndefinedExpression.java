package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Check whether a {@linkplain Value} was resolved as {@linkplain Value#UNDEFINED}.<br>
 * This can happen when the {@linkplain ValueReferenceResolver} is not able to properly resolve a
 * reference or an expression is using {@linkplain Value#UNDEFINED} value in its computation.
 */
public final class IsUndefinedExpression implements BooleanExpression {
  private final ValueExpression<?> valueExpression;

  public IsUndefinedExpression(ValueExpression<?> valueExpression) {
    this.valueExpression = valueExpression;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    if (valueExpression == null) {
      return Boolean.FALSE;
    }
    Value<?> value = valueExpression.evaluate(valueRefResolver);
    return value.isUndefined() ? Boolean.TRUE : Boolean.FALSE;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getValueExpression() {
    return valueExpression;
  }
}
