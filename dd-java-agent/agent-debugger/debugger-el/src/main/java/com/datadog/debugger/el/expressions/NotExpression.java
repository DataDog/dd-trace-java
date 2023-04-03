package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Visitor;
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
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public BooleanExpression getPredicate() {
    return predicate;
  }
}
