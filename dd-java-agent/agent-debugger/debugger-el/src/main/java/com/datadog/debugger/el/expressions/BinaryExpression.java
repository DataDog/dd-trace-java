package com.datadog.debugger.el.expressions;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Takes two {@linkplain BooleanExpression} instances and combines them with the given {@link
 * BinaryOperator operator}.
 */
public final class BinaryExpression implements BooleanExpression {
  protected final BooleanExpression left;
  protected final BooleanExpression right;
  private final BinaryOperator operator;

  public BinaryExpression(
      BooleanExpression left, BooleanExpression right, BinaryOperator operator) {
    this.left = left == null ? ctx -> Boolean.FALSE : left;
    this.right = right == null ? ctx -> Boolean.FALSE : right;
    this.operator = operator;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return operator.apply(left.evaluate(valueRefResolver), right.evaluate(valueRefResolver));
  }

  @Override
  public String toString() {
    return "BinaryExpression{"
        + "left="
        + left
        + ", right="
        + right
        + ", operator="
        + operator
        + '}';
  }

  @Override
  public String prettyPrint() {
    return left.prettyPrint() + " " + operator.prettyPrint() + " " + right.prettyPrint();
  }
}
