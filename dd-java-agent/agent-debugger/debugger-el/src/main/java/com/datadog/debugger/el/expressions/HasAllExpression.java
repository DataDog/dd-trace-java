package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedList;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedMap;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedSet;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.evaluateTargetCollection;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.SetValue;
import datadog.trace.bootstrap.debugger.CapturedContext.CapturedValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
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
    Value<?> value = evaluateTargetCollection(valueExpression, this, valueRefResolver);
    if (value instanceof ListValue) {
      ListValue collection = (ListValue) value;
      checkSupportedList(collection, this);
      if (collection.isEmpty()) {
        // always return TRUE for empty values (cf vacuous truth, see also Stream::allMatch)
        return Boolean.TRUE;
      }
      int len = collection.count();
      try {
        for (int i = 0; i < len; i++) {
          valueRefResolver.addExtension(
              ValueReferences.ITERATOR_EXTENSION_NAME, CapturedValue.of(collection.get(i)));
          if (!filterPredicateExpression.evaluate(valueRefResolver)) {
            return Boolean.FALSE;
          }
        }
        return Boolean.TRUE;
      } finally {
        valueRefResolver.removeExtension(ValueReferences.ITERATOR_EXTENSION_NAME);
      }
    }
    if (value instanceof MapValue) {
      MapValue map = (MapValue) value;
      checkSupportedMap(map, this);
      if (map.isEmpty()) {
        // always return TRUE for empty values (cf vacuous truth, see also Stream::allMatch)
        return Boolean.TRUE;
      }
      try {
        for (Value<?> key : map.getKeys()) {
          Value<?> val = key.isUndefined() ? Value.undefinedValue() : map.get(key);
          valueRefResolver.addExtension(ValueReferences.KEY_EXTENSION_NAME, CapturedValue.of(key));
          valueRefResolver.addExtension(
              ValueReferences.VALUE_EXTENSION_NAME, CapturedValue.of(val));
          valueRefResolver.addExtension(
              ValueReferences.ITERATOR_EXTENSION_NAME,
              CapturedValue.of(new MapValue.Entry(key, val)));
          if (!filterPredicateExpression.evaluate(valueRefResolver)) {
            return Boolean.FALSE;
          }
        }
        return Boolean.TRUE;
      } finally {
        valueRefResolver.removeExtension(ValueReferences.ITERATOR_EXTENSION_NAME);
        valueRefResolver.removeExtension(ValueReferences.KEY_EXTENSION_NAME);
        valueRefResolver.removeExtension(ValueReferences.VALUE_EXTENSION_NAME);
      }
    }
    if (value instanceof SetValue) {
      SetValue set = (SetValue) value;
      Set<?> setHolder = checkSupportedSet(set, this);
      if (set.isEmpty()) {
        // always return TRUE for empty values (cf vacuous truth, see also Stream::allMatch)
        return Boolean.TRUE;
      }
      try {
        for (Object val : setHolder) {
          valueRefResolver.addExtension(
              ValueReferences.ITERATOR_EXTENSION_NAME, CapturedValue.of(val));
          if (!filterPredicateExpression.evaluate(valueRefResolver)) {
            return Boolean.FALSE;
          }
        }
        return Boolean.TRUE;
      } finally {
        valueRefResolver.removeExtension(ValueReferences.ITERATOR_EXTENSION_NAME);
      }
    }
    throw new EvaluationException(
        "Unsupported collection class: " + value.getValue().getClass().getTypeName(), print(this));
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
