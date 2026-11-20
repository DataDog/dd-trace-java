package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Generated;
import java.util.Objects;

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

  @Generated
  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    MatchingExpression that = (MatchingExpression) o;
    return Objects.equals(valueExpression, that.valueExpression)
        && Objects.equals(filterPredicateExpression, that.filterPredicateExpression);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(valueExpression, filterPredicateExpression);
  }
}
