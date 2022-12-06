package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.CollectionValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * Checks whether a {@linkplain Value} is empty.<br>
 * The result will be {@literal true} for empty string or collection, {@literal false} otherwise.
 */
public final class IsEmptyExpression implements PredicateExpression {
  private final ValueExpression<?> valueExpression;

  public IsEmptyExpression(ValueExpression<?> valueExpression) {
    this.valueExpression = valueExpression == null ? ValueExpression.NULL : valueExpression;
  }

  @Override
  public Predicate evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> value = valueExpression.evaluate(valueRefResolver);
    if (value.isNull() || value.isUndefined()) {
      return Predicate.TRUE;
    }
    if (value instanceof CollectionValue) {
      return ((CollectionValue) value)::isEmpty;
    } else if (value instanceof StringValue) {
      return ((StringValue) value)::isEmpty;
    }
    return Predicate.FALSE;
  }
}
