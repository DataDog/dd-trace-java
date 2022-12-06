package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** TODO: Primordial support for 'debugger watches' support */
public final class IfExpression implements Expression<Void> {
  private final PredicateExpression test;
  private final Expression<?> expression;

  public IfExpression(PredicateExpression test, Expression<?> expression) {
    this.test = test == null ? PredicateExpression.FALSE : test;
    this.expression = expression == null ? ValueExpression.NULL : expression;
  }

  @Override
  public Void evaluate(ValueReferenceResolver valueRefResolver) {
    if (test.evaluate(valueRefResolver).test()) {
      expression.evaluate(valueRefResolver);
    }
    return null;
  }
}
