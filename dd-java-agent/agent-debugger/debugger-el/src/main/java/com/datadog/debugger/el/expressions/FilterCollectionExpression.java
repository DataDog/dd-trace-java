package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.CollectionValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filters a {@link CollectionValue collection} (list or map) using the given {@linkplain
 * PredicateExpression} filter.
 */
public final class FilterCollectionExpression implements ValueExpression<CollectionValue<?>> {
  private static final Logger log = LoggerFactory.getLogger(FilterCollectionExpression.class);

  private final ValueExpression<?> source;
  private final PredicateExpression filterExpression;

  public FilterCollectionExpression(
      ValueExpression<?> source, PredicateExpression filterExpression) {
    this.source = source;
    this.filterExpression = filterExpression;
  }

  @Override
  public CollectionValue<?> evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> collectionValue = source.evaluate(valueRefResolver);
    if (collectionValue.isUndefined()) {
      return (collectionValue instanceof CollectionValue)
          ? (CollectionValue<?>) collectionValue
          : CollectionValue.UNDEFINED;
    } else if (collectionValue.isNull()) {
      return (collectionValue instanceof CollectionValue)
          ? (CollectionValue<?>) collectionValue
          : CollectionValue.NULL;
    }
    if (collectionValue instanceof ListValue) {
      ListValue materialized = (ListValue) collectionValue;
      Collection<Object> filtered = new ArrayList<>();
      int len = materialized.count();
      for (int i = 0; i < len; i++) {
        Object value = materialized.get(i).getValue();
        if (filterExpression
            .evaluate(
                valueRefResolver.withExtensions(
                    Collections.singletonMap(ValueReferences.ITERATOR_EXTENSION_NAME, value)))
            .test()) {
          filtered.add(value);
        }
      }
      return new ListValue(filtered);
    } else if (collectionValue instanceof MapValue) {
      MapValue materialized = (MapValue) collectionValue;
      Map<Object, Object> filtered = new HashMap<>();

      for (Value<?> key : materialized.getKeys()) {
        Value<?> value = key.isUndefined() ? Value.undefinedValue() : materialized.get(key);
        if (filterExpression
            .evaluate(
                valueRefResolver.withExtensions(
                    Collections.singletonMap(
                        ValueReferences.ITERATOR_EXTENSION_NAME, new MapValue.Entry(key, value))))
            .test()) {
          filtered.put(key.getValue(), value.getValue());
        }
      }
      return new MapValue(filtered);
    }
    log.warn("Unsupported collection type {}", collectionValue.getValue().getClass().getName());
    return CollectionValue.UNDEFINED;
  }
}
