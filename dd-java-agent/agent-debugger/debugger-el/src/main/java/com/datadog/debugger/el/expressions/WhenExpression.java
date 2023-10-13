package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** The entry-point expression for the debugger EL */
public final class WhenExpression implements BooleanExpression {
  private final BooleanExpression expression;

  public WhenExpression(BooleanExpression expression) {
    this.expression = expression;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return expression.evaluate(valueRefResolver);
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public BooleanExpression getExpression() {
    return expression;
  }
}
