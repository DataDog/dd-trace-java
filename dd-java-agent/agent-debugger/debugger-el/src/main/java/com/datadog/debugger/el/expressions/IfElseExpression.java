package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** TODO: Primordial support for 'debugger watches' support */
public final class IfElseExpression implements Expression<Void> {
  private final BooleanExpression test;
  private final Expression<?> thenExpression;
  private final Expression<?> elseExpression;

  public IfElseExpression(
      BooleanExpression test, Expression<?> thenExpression, Expression<?> elseExpression) {
    this.test = test == null ? BooleanExpression.FALSE : test;
    this.thenExpression = thenExpression == null ? ValueExpression.NULL : thenExpression;
    this.elseExpression = elseExpression == null ? ValueExpression.NULL : elseExpression;
  }

  @Override
  public Void evaluate(ValueReferenceResolver valueRefResolver) {
    if (test.evaluate(valueRefResolver)) {
      thenExpression.evaluate(valueRefResolver);
    } else {
      elseExpression.evaluate(valueRefResolver);
    }
    return null;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public BooleanExpression getTest() {
    return test;
  }

  public Expression<?> getThenExpression() {
    return thenExpression;
  }

  public Expression<?> getElseExpression() {
    return elseExpression;
  }
}
