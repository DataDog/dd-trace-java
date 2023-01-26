package com.datadog.debugger.el.expressions;

public enum BinaryOperator {
  AND("&&") {
    @Override
    public Boolean apply(Boolean left, Boolean right) {
      return left && right;
    }
  },
  OR("||") {
    @Override
    public Boolean apply(Boolean left, Boolean right) {
      return left || right;
    }
  };

  private String symbol;

  BinaryOperator(String symbol) {
    this.symbol = symbol;
  }

  public abstract Boolean apply(Boolean left, Boolean right);

  public String prettyPrint() {
    return symbol;
  }
}
