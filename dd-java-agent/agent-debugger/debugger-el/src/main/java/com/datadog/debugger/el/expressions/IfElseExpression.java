package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.expressions.ExpressionHelper.checkTimeout;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Visitor;

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
  public Void evaluate(EvalContext evalContext) {
    if (test.evaluate(evalContext)) {
      thenExpression.evaluate(evalContext);
    } else {
      elseExpression.evaluate(evalContext);
    }
    checkTimeout(evalContext.getTimeoutChecker(), this);
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
