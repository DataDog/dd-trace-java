package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** TODO: Primordial support for 'debugger watches' support */
public final class IfElseExpression implements Expression<Void> {
  private final PredicateExpression test;
  private final Expression<?> thenExpression;
  private final Expression<?> elseExpression;

  public IfElseExpression(
      PredicateExpression test, Expression<?> thenExpression, Expression<?> elseExpression) {
    this.test = test == null ? PredicateExpression.FALSE : test;
    this.thenExpression = thenExpression == null ? ValueExpression.NULL : thenExpression;
    this.elseExpression = elseExpression == null ? ValueExpression.NULL : elseExpression;
  }

  @Override
  public Void evaluate(ValueReferenceResolver valueRefResolver) {
    if (test.evaluate(valueRefResolver).test()) {
      thenExpression.evaluate(valueRefResolver);
    } else {
      elseExpression.evaluate(valueRefResolver);
    }
    return null;
  }
}
