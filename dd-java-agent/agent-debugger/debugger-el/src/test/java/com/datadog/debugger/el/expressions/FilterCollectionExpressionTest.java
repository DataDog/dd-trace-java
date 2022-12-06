package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.StaticValueRefResolver;
import com.datadog.debugger.el.values.CollectionValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterCollectionExpressionTest {
  @Test
  void testMatchingList() {
    ListValue collection = new ListValue(new int[] {1, 2, 3});

    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertNotEquals(collection, filtered);
    assertEquals(1, filtered.count());
    assertFalse(filtered.isEmpty());
    assertFalse(filtered.isNull());
    assertFalse(filtered.isUndefined());
  }

  @Test
  void testEmptyList() {
    ListValue collection = new ListValue(new int[0]);
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertNotEquals(collection, filtered);
    assertTrue(filtered.isEmpty());
    assertFalse(filtered.isNull());
    assertFalse(filtered.isUndefined());
  }

  @Test
  void testNullList() {
    ListValue collection = new ListValue(null);
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertEquals(collection, filtered);
    assertTrue(filtered.isNull());
  }

  @Test
  void testNullObjectList() {
    ListValue collection = new ListValue(Values.NULL_OBJECT);
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertEquals(collection, filtered);
    assertTrue(filtered.isNull());
  }

  @Test
  void testUndefinedList() {
    ListValue collection = new ListValue(Values.UNDEFINED_OBJECT);
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertEquals(collection, filtered);
    assertTrue(filtered.isUndefined());
  }

  @Test
  void testMatchingMap() {
    Map<String, Integer> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    map.put("c", 3);
    MapValue collection = new MapValue(map);

    FilterCollectionExpression expression =
        new FilterCollectionExpression(
            collection, eq(ref(ValueReferences.ITERATOR_REF + ".key"), value("b")));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertNotEquals(collection, filtered);
    assertEquals(1, filtered.count());
    assertFalse(filtered.isEmpty());
    assertFalse(filtered.isNull());
    assertFalse(filtered.isUndefined());

    expression =
        new FilterCollectionExpression(
            collection, lt(ref(ValueReferences.ITERATOR_REF + ".value"), value(2)));
    filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertNotEquals(collection, filtered);
    assertEquals(1, filtered.count());
    assertFalse(filtered.isEmpty());
    assertFalse(filtered.isNull());
    assertFalse(filtered.isUndefined());
  }

  @Test
  void testEmptyMap() {
    MapValue collection = new MapValue(Collections.emptyMap());
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertNotEquals(collection, filtered);
    assertTrue(filtered.isEmpty());
    assertFalse(filtered.isNull());
    assertFalse(filtered.isUndefined());
  }

  @Test
  void testNullMap() {
    MapValue collection = new MapValue(null);
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertEquals(collection, filtered);
    assertTrue(filtered.isNull());
  }

  @Test
  void testNullObjectMap() {
    MapValue collection = new MapValue(Values.NULL_OBJECT);
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertEquals(collection, filtered);
    assertTrue(filtered.isNull());
  }

  @Test
  void testUndefinedMap() {
    MapValue collection = new MapValue(Values.UNDEFINED_OBJECT);
    FilterCollectionExpression expression =
        new FilterCollectionExpression(collection, lt(ref(ValueReferences.ITERATOR_REF), value(2)));
    CollectionValue<?> filtered = expression.evaluate(StaticValueRefResolver.self(this));
    assertEquals(collection, filtered);
    assertTrue(filtered.isUndefined());
  }
}
