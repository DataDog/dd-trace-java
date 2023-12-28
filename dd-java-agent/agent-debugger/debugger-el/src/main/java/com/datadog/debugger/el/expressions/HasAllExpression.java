package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.util.Collections;

/**
 * Checks a {@linkplain Value} against the given {@link BooleanExpression filter expression} to make
 * sure that all elements are conforming to the filter.<br>
 * If the provided value is of {@linkplain com.datadog.debugger.el.values.CollectionValue} type the
 * check will be performed for each contained element. Otherwise, the value itself would be matched
 * using the filter.
 */
public final class HasAllExpression extends MatchingExpression {
  public HasAllExpression(ValueExpression<?> valueExpression, BooleanExpression filterExpression) {
    super(valueExpression, filterExpression);
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    if (valueExpression == null) {
      return Boolean.FALSE;
    }

    Value<?> value = valueExpression.evaluate(valueRefResolver);
    if (value.isUndefined()) {
      return Boolean.FALSE;
    }
    if (value instanceof ListValue) {
      ListValue collection = (ListValue) value;
      if (collection.isEmpty()) {
        // always return FALSE for empty values
        return Boolean.FALSE;
      }
      int len = collection.count();
      for (int i = 0; i < len; i++) {
        Value<?> val = collection.get(i);
        if (!filterPredicateExpression.evaluate(
            valueRefResolver.withExtensions(
                Collections.singletonMap(ValueReferences.ITERATOR_EXTENSION_NAME, val)))) {
          return Boolean.FALSE;
        }
      }
      return Boolean.TRUE;
    } else if (value instanceof MapValue) {
      MapValue map = (MapValue) value;
      if (map.isEmpty()) {
        // always return FALSE for empty values
        return Boolean.FALSE;
      }
      for (Value<?> key : map.getKeys()) {
        Value<?> val = key.isUndefined() ? Value.undefinedValue() : map.get(key);
        if (!filterPredicateExpression.evaluate(
            valueRefResolver.withExtensions(
                Collections.singletonMap(
                    ValueReferences.ITERATOR_EXTENSION_NAME, new MapValue.Entry(key, val))))) {
          return Boolean.FALSE;
        }
      }
      return Boolean.TRUE;
    } else {
      return filterPredicateExpression.evaluate(
          valueRefResolver.withExtensions(
              Collections.singletonMap(ValueReferences.ITERATOR_EXTENSION_NAME, value)));
    }
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
