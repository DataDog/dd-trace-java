package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Visitor;
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
    this.left = left == null ? BooleanExpression.FALSE : left;
    this.right = right == null ? BooleanExpression.FALSE : right;
    this.operator = operator;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return operator.apply(left, right, valueRefResolver);
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
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public BooleanExpression getLeft() {
    return left;
  }

  public BooleanExpression getRight() {
    return right;
  }

  public BinaryOperator getOperator() {
    return operator;
  }
}
