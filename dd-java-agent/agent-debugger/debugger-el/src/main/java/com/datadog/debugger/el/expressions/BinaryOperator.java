package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

public enum BinaryOperator {
  AND("&&") {
    @Override
    public Boolean apply(
        BooleanExpression left, BooleanExpression right, ValueReferenceResolver resolver) {
      return left.evaluate(resolver) && right.evaluate(resolver);
    }
  },
  OR("||") {
    @Override
    public Boolean apply(
        BooleanExpression left, BooleanExpression right, ValueReferenceResolver resolver) {
      return left.evaluate(resolver) || right.evaluate(resolver);
    }
  };

  private final String symbol;

  BinaryOperator(String symbol) {
    this.symbol = symbol;
  }

  public abstract Boolean apply(
      BooleanExpression left, BooleanExpression right, ValueReferenceResolver resolver);

  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public String getSymbol() {
    return symbol;
  }
}
