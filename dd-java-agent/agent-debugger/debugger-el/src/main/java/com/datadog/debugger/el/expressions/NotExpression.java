package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.Expression.nullSafePrettyPrint;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** Will negate the resolved {@linkplain BooleanExpression} */
public final class NotExpression implements BooleanExpression {
  private final BooleanExpression predicate;

  public NotExpression(BooleanExpression predicate) {
    this.predicate = predicate == null ? (BooleanExpression.FALSE) : predicate;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return !predicate.evaluate(valueRefResolver);
  }

  @Override
  public String prettyPrint() {
    return "not(" + nullSafePrettyPrint(predicate) + ")";
  }
}
