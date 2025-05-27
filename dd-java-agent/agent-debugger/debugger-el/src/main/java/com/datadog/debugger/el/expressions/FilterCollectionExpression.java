package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedList;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedMap;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedSet;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.evaluateTargetCollection;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.CollectionValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.SetValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filters a {@link CollectionValue collection} (list or map) using the given {@linkplain
 * BooleanExpression} filter.
 */
public final class FilterCollectionExpression implements ValueExpression<CollectionValue<?>> {
  private static final Logger log = LoggerFactory.getLogger(FilterCollectionExpression.class);

  private final ValueExpression<?> source;
  private final BooleanExpression filterExpression;

  public FilterCollectionExpression(ValueExpression<?> source, BooleanExpression filterExpression) {
    this.source = source;
    this.filterExpression = filterExpression;
  }

  @Override
  public CollectionValue<?> evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> collectionValue = evaluateTargetCollection(source, filterExpression, valueRefResolver);
    if (collectionValue instanceof ListValue) {
      ListValue materialized = (ListValue) collectionValue;
      checkSupportedList(materialized, this);
      Collection<Object> filtered = new ArrayList<>();
      int len = materialized.count();
      for (int i = 0; i < len; i++) {
        Object value = materialized.get(i).getValue();
        if (filterExpression.evaluate(
            valueRefResolver.withExtensions(
                Collections.singletonMap(ValueReferences.ITERATOR_EXTENSION_NAME, value)))) {
          filtered.add(value);
        }
      }
      return new ListValue(filtered);
    } else if (collectionValue instanceof MapValue) {
      MapValue materialized = (MapValue) collectionValue;
      checkSupportedMap(materialized, this);
      Map<Object, Object> filtered = new HashMap<>();
      for (Value<?> key : materialized.getKeys()) {
        Value<?> value = key.isUndefined() ? Value.undefinedValue() : materialized.get(key);
        Map<String, Object> valueRefExtensions = new HashMap<>();
        valueRefExtensions.put(ValueReferences.KEY_EXTENSION_NAME, key);
        valueRefExtensions.put(ValueReferences.VALUE_EXTENSION_NAME, value);
        valueRefExtensions.put(
            ValueReferences.ITERATOR_EXTENSION_NAME, new MapValue.Entry(key, value));
        if (filterExpression.evaluate(valueRefResolver.withExtensions(valueRefExtensions))) {
          filtered.put(key.getValue(), value.getValue());
        }
      }
      return new MapValue(filtered);
    } else if (collectionValue instanceof SetValue) {
      SetValue materialized = (SetValue) collectionValue;
      Collection<Object> filtered = new HashSet<>();
      Set<?> setHolder = checkSupportedSet(materialized, this);
      for (Object value : setHolder) {
        if (filterExpression.evaluate(
            valueRefResolver.withExtensions(
                Collections.singletonMap(ValueReferences.ITERATOR_EXTENSION_NAME, value)))) {
          filtered.add(value);
        }
      }
      return new SetValue(filtered);
    }
    throw new EvaluationException(
        "Unsupported collection type: " + collectionValue.getValue().getClass().getTypeName(),
        print(this));
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getSource() {
    return source;
  }

  public BooleanExpression getFilterExpression() {
    return filterExpression;
  }
}
