package com.datadog.debugger.el.expressions;

/** Abstract super class for expressions performing matching of a value against a predicate. */
abstract class MatchingExpression implements BooleanExpression {
  protected final ValueExpression<?> valueExpression;
  protected final BooleanExpression filterPredicateExpression;

  public MatchingExpression(
      ValueExpression<?> valueExpression, BooleanExpression filterPredicateExpression) {
    this.valueExpression = valueExpression;
    this.filterPredicateExpression =
        filterPredicateExpression == null ? BooleanExpression.TRUE : filterPredicateExpression;
  }

  public ValueExpression<?> getValueExpression() {
    return valueExpression;
  }

  public BooleanExpression getFilterPredicateExpression() {
    return filterPredicateExpression;
  }
}
