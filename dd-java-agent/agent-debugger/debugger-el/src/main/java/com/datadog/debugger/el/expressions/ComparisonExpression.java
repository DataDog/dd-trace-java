package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Takes two {@linkplain ValueExpression} instances and compares them using the given {@link
 * ComparisonOperator operator}.
 */
public class ComparisonExpression implements BooleanExpression {
  private final ValueExpression<?> left;
  private final ValueExpression<?> right;
  private final ComparisonOperator operator;

  public ComparisonExpression(
      ValueExpression<?> left, ValueExpression<?> right, ComparisonOperator operator) {
    this.left = left == null ? ValueExpression.NULL : left;
    this.right = right == null ? ValueRefExpression.NULL : right;
    this.operator = operator;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> leftValue = left.evaluate(valueRefResolver);
    if (leftValue.isUndefined()) {
      return Boolean.FALSE;
    }
    Value<?> rightValue = right.evaluate(valueRefResolver);
    if (rightValue.isUndefined()) {
      return Boolean.FALSE;
    }
    return operator.apply(leftValue, rightValue);
  }
}
