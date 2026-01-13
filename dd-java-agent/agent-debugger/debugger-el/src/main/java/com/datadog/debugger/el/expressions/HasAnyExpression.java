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
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.util.Set;

/**
 * Checks a {@linkplain Value} against the given {@link BooleanExpression filter expression} to make
 * sure that any of the elements are conforming to the filter.<br>
 * If the provided value is of {@linkplain com.datadog.debugger.el.values.CollectionValue} type the
 * check will be performed for each contained element. Otherwise, the value itself would be matched
 * using the filter.
 */
public final class HasAnyExpression extends MatchingExpression {
  public HasAnyExpression(
      ValueExpression<?> valueExpression, BooleanExpression filterPredicateExpression) {
    super(valueExpression, filterPredicateExpression);
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> value = evaluateTargetCollection(valueExpression, this, valueRefResolver);
    if (value instanceof ListValue) {
      ListValue collection = (ListValue) value;
      checkSupportedList(collection, this);
      if (collection.isEmpty()) {
        // always return FALSE for empty collection
        return Boolean.FALSE;
      }
      try {
        int len = collection.count();
        for (int i = 0; i < len; i++) {
          valueRefResolver.addExtension(ValueReferences.ITERATOR_EXTENSION_NAME, collection.get(i));
          if (filterPredicateExpression.evaluate(valueRefResolver)) {
            return Boolean.TRUE;
          }
        }
        return Boolean.FALSE;

      } catch (IllegalArgumentException | UnsupportedOperationException ex) {
        throw new EvaluationException(ex.getMessage(), print(this));
      } finally {
        valueRefResolver.removeExtension(ValueReferences.ITERATOR_EXTENSION_NAME);
      }
    }
    if (value instanceof MapValue) {
      MapValue map = (MapValue) value;
      checkSupportedMap(map, this);
      try {
        if (map.isEmpty()) {
          return Boolean.FALSE;
        }
        for (Value<?> key : map.getKeys()) {
          Value<?> val = key.isUndefined() ? Value.undefinedValue() : map.get(key);
          valueRefResolver.addExtension(ValueReferences.KEY_EXTENSION_NAME, key);
          valueRefResolver.addExtension(ValueReferences.VALUE_EXTENSION_NAME, val);
          valueRefResolver.addExtension(
              ValueReferences.ITERATOR_EXTENSION_NAME, new MapValue.Entry(key, val));
          if (filterPredicateExpression.evaluate(valueRefResolver)) {
            return Boolean.TRUE;
          }
        }
        return Boolean.FALSE;
      } catch (IllegalArgumentException | UnsupportedOperationException ex) {
        throw new EvaluationException(ex.getMessage(), print(this));
      } finally {
        valueRefResolver.removeExtension(ValueReferences.ITERATOR_EXTENSION_NAME);
        valueRefResolver.removeExtension(ValueReferences.KEY_EXTENSION_NAME);
        valueRefResolver.removeExtension(ValueReferences.VALUE_EXTENSION_NAME);
      }
    }
    if (value instanceof SetValue) {
      SetValue set = (SetValue) value;
      Set<?> setHolder = checkSupportedSet(set, this);
      try {
        if (set.isEmpty()) {
          return Boolean.FALSE;
        }
        for (Object val : setHolder) {
          valueRefResolver.addExtension(ValueReferences.ITERATOR_EXTENSION_NAME, Value.of(val));
          if (filterPredicateExpression.evaluate(valueRefResolver)) {
            return Boolean.TRUE;
          }
        }
        return Boolean.FALSE;
      } catch (IllegalArgumentException | UnsupportedOperationException ex) {
        throw new EvaluationException(ex.getMessage(), print(this));
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
