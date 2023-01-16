package com.datadog.debugger.el.expressions;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** Will negate the resolved {@linkplain BooleanExpression} */
public final class NotExpression implements BooleanExpression {
  private final BooleanExpression predicate;

  public NotExpression(BooleanExpression predicate) {
    this.predicate = predicate == null ? (ctx -> Boolean.FALSE) : predicate;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    return !predicate.evaluate(valueRefResolver);
  }
}
