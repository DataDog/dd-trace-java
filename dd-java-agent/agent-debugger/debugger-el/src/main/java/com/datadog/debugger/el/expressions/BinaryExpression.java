package com.datadog.debugger.el.expressions;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Takes two {@linkplain PredicateExpression} instances and combines them with the given {@link
 * BinaryOperator operator}.
 */
public final class BinaryExpression implements PredicateExpression {
  protected final PredicateExpression left;
  protected final PredicateExpression right;
  private final BinaryOperator operator;

  public BinaryExpression(
      PredicateExpression left, PredicateExpression right, BinaryOperator operator) {
    this.left = left == null ? ctx -> Boolean.FALSE : left;
    this.right = right == null ? ctx -> Boolean.FALSE : right;
    this.operator = operator;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return operator.apply(left.evaluate(valueRefResolver), right.evaluate(valueRefResolver));
  }
}
