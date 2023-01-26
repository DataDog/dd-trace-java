package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
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
  public String prettyPrint() {
    return "when(" + Expression.nullSafePrettyPrint(expression) + ")";
  }
}
