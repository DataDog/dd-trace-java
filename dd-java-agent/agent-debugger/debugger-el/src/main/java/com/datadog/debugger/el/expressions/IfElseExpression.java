package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.Expression.nullSafePrettyPrint;

import com.datadog.debugger.el.Expression;
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
  public String prettyPrint() {
    return "if "
        + nullSafePrettyPrint(test)
        + " then "
        + nullSafePrettyPrint(thenExpression)
        + " else "
        + nullSafePrettyPrint(elseExpression);
  }
}
