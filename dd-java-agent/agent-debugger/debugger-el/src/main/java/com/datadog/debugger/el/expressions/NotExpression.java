package com.datadog.debugger.el.expressions;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** Will negate the resolved {@linkplain PredicateExpression} */
public final class NotExpression implements PredicateExpression {
  private final PredicateExpression predicate;

  public NotExpression(PredicateExpression predicate) {
    this.predicate = predicate == null ? (ctx -> Boolean.FALSE) : predicate;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return !predicate.evaluate(valueRefResolver);
  }
}
