package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.predicates.NotPredicate;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** Will negate the resolved {@linkplain Predicate} */
public final class NotExpression implements PredicateExpression {
  private final PredicateExpression predicate;

  public NotExpression(PredicateExpression predicate) {
    this.predicate = predicate == null ? (ctx -> Predicate.FALSE) : predicate;
  }

  @Override
  public Predicate evaluate(ValueReferenceResolver valueRefResolver) {
    return new NotPredicate(predicate.evaluate(valueRefResolver));
  }
}
