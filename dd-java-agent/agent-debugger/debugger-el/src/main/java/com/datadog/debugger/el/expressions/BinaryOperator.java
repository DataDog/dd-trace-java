package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.Visitor;

public enum BinaryOperator {
  AND("&&") {
    @Override
    public Boolean apply(BooleanExpression left, BooleanExpression right, EvalContext evalContext) {
      return left.evaluate(evalContext) && right.evaluate(evalContext);
    }
  },
  OR("||") {
    @Override
    public Boolean apply(BooleanExpression left, BooleanExpression right, EvalContext evalContext) {
      return left.evaluate(evalContext) || right.evaluate(evalContext);
    }
  };

  private final String symbol;

  BinaryOperator(String symbol) {
    this.symbol = symbol;
  }

  public abstract Boolean apply(
      BooleanExpression left, BooleanExpression right, EvalContext evalContext);

  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public String getSymbol() {
    return symbol;
  }
}
