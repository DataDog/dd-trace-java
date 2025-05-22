package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.SetValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    Value<?> value = valueExpression.evaluate(valueRefResolver);
    if (value.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (value.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    if (value instanceof ListValue) {
      ListValue collection = (ListValue) value;
      if (collection.isEmpty()) {
        // always return TRUE for empty values (cf vacuous truth, see also Stream::allMatch)
        return Boolean.TRUE;
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
    }
    if (value instanceof MapValue) {
      MapValue map = (MapValue) value;
      if (map.isEmpty()) {
        // always return TRUE for empty values (cf vacuous truth, see also Stream::allMatch)
        return Boolean.TRUE;
      }
      for (Value<?> key : map.getKeys()) {
        Value<?> val = key.isUndefined() ? Value.undefinedValue() : map.get(key);
        Map<String, Object> valueRefExtensions = new HashMap<>();
        valueRefExtensions.put(ValueReferences.KEY_EXTENSION_NAME, key);
        valueRefExtensions.put(ValueReferences.VALUE_EXTENSION_NAME, val);
        valueRefExtensions.put(
            ValueReferences.ITERATOR_EXTENSION_NAME, new MapValue.Entry(key, val));
        if (!filterPredicateExpression.evaluate(
            valueRefResolver.withExtensions(valueRefExtensions))) {
          return Boolean.FALSE;
        }
      }
      return Boolean.TRUE;
    }
    if (value instanceof SetValue) {
      SetValue set = (SetValue) value;
      if (set.isEmpty()) {
        // always return TRUE for empty values (cf vacuous truth, see also Stream::allMatch)
        return Boolean.TRUE;
      }
      Set<?> setHolder = (Set<?>) set.getSetHolder();
      if (WellKnownClasses.isSafe(setHolder)) {
        for (Object val : setHolder) {
          if (!filterPredicateExpression.evaluate(
              valueRefResolver.withExtensions(
                  Collections.singletonMap(ValueReferences.ITERATOR_EXTENSION_NAME, val)))) {
            return Boolean.FALSE;
          }
        }
        return Boolean.TRUE;
      }
      throw new EvaluationException(
          "Unsupported Set class: " + setHolder.getClass().getTypeName(),
          PrettyPrintVisitor.print(this));
    }
    return filterPredicateExpression.evaluate(
        valueRefResolver.withExtensions(
            Collections.singletonMap(ValueReferences.ITERATOR_EXTENSION_NAME, value)));
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
