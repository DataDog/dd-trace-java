package com.datadog.debugger.el.expressions;

/** Abstract super class for expressions performing matching of a value against a predicate. */
abstract class MatchingExpression implements PredicateExpression {
  protected final ValueExpression<?> valueExpression;
  protected final PredicateExpression filterPredicateExpression;

  public MatchingExpression(
      ValueExpression<?> valueExpression, PredicateExpression filterPredicateExpression) {
    this.valueExpression = valueExpression;
    this.filterPredicateExpression =
        filterPredicateExpression == null ? PredicateExpression.TRUE : filterPredicateExpression;
  }
}
