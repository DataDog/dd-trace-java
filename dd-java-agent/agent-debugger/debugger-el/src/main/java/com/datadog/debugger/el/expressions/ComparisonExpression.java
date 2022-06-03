package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.predicates.ValuePredicate;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Takes two {@linkplain ValueExpression} instances and compares them using the given {@link
 * ValuePredicate.Combiner combiner}.
 */
public final class ComparisonExpression implements PredicateExpression {
  private final ValueExpression<?> left;
  private final ValueExpression<?> right;
  private final ValuePredicate.Combiner combiner;

  public ComparisonExpression(
      ValueExpression<?> left, ValueExpression<?> right, ValuePredicate.Combiner combiner) {
    this.left = left == null ? ValueExpression.NULL : left;
    this.right = right == null ? ValueRefExpression.NULL : right;
    this.combiner = combiner;
  }

  @Override
  public Predicate evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> leftValue = left.evaluate(valueRefResolver);
    if (leftValue.isUndefined()) {
      return Predicate.FALSE;
    }
    Value<?> rightValue = right.evaluate(valueRefResolver);
    if (rightValue.isUndefined()) {
      return Predicate.FALSE;
    }
    return combiner.get(leftValue, rightValue);
  }
}
