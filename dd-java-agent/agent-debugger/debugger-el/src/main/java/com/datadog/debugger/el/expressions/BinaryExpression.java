package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.predicates.BinaryPredicate;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Takes two {@linkplain PredicateExpression} instances and combines them with the given {@link
 * BinaryPredicate.Combiner combiner}.
 */
public final class BinaryExpression implements PredicateExpression {
  protected final PredicateExpression left;
  protected final PredicateExpression right;
  private final BinaryPredicate.Combiner combiner;

  public BinaryExpression(
      PredicateExpression left, PredicateExpression right, BinaryPredicate.Combiner combiner) {
    this.left = left == null ? ctx -> Predicate.FALSE : left;
    this.right = right == null ? ctx -> Predicate.FALSE : right;
    this.combiner = combiner;
  }

  @Override
  public Predicate evaluate(ValueReferenceResolver valueRefResolver) {
    return combiner.get(left.evaluate(valueRefResolver), right.evaluate(valueRefResolver));
  }
}
