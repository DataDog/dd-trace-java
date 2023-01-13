package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** TODO: Primordial support for 'debugger watches' support */
public final class IfExpression implements Expression<Void> {
  private final BooleanExpression test;
  private final Expression<?> expression;

  public IfExpression(BooleanExpression test, Expression<?> expression) {
    this.test = test == null ? BooleanExpression.FALSE : test;
    this.expression = expression == null ? ValueExpression.NULL : expression;
  }

  @Override
  public Void evaluate(ValueReferenceResolver valueRefResolver) {
    if (test.evaluate(valueRefResolver)) {
      expression.evaluate(valueRefResolver);
    }
    return null;
  }

  @Override
  public String prettyPrint() {
    return "if " + test.prettyPrint() + " then " + expression.prettyPrint();
  }
}
