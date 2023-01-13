package com.datadog.debugger.el.expressions;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** The entry-point expression for the debugger EL */
public final class WhenExpression implements PredicateExpression {
  private final PredicateExpression expression;

  public WhenExpression(PredicateExpression expression) {
    this.expression = expression;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return expression.evaluate(valueRefResolver);
  }
}
