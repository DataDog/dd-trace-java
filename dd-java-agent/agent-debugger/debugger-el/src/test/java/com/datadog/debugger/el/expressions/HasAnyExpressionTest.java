package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.SetValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HasAnyExpressionTest {
  private final int testField = 10;

  @Test
  void testNullPredicate() {
    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    HasAnyExpression nullExpression = new HasAnyExpression(null, null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {true})", print(nullExpression));
    HasAnyExpression undefinedExpression =
        new HasAnyExpression(value(Values.UNDEFINED_OBJECT), null);
    exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("any(UNDEFINED, {true})", print(undefinedExpression));
    HasAnyExpression expression = new HasAnyExpression(value(new Object[] {this}), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("any(java.lang.Object[], {true})", print(expression));
    expression = new HasAnyExpression(value(Collections.singletonList(this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("any(List, {true})", print(expression));
    expression = new HasAnyExpression(value(Collections.singletonMap(this, this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("any(Map, {true})", print(expression));
  }

  @Test
  void testNullHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAnyExpression nullExpression1 = any(null, BooleanExpression.TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression1.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {true})", print(nullExpression1));

    HasAnyExpression nullExpression2 = any(null, BooleanExpression.FALSE);
    exception = assertThrows(EvaluationException.class, () -> nullExpression2.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {false})", print(nullExpression2));

    HasAnyExpression nullExpression3 = any(null, eq(ref("testField"), value(10)));
    exception = assertThrows(EvaluationException.class, () -> nullExpression3.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {testField == 10})", print(nullExpression3));
  }

  @Test
  void testUndefinedHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAnyExpression undefinedExpression = any(value(Values.UNDEFINED_OBJECT), TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("any(UNDEFINED, {true})", print(undefinedExpression));

    HasAnyExpression nullExpression = any(null, FALSE);
    exception = assertThrows(EvaluationException.class, () -> nullExpression.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {false})", print(nullExpression));

    HasAnyExpression undefinedExpression2 =
        any(value(Values.UNDEFINED_OBJECT), eq(ref("testField"), value(10)));
    exception = assertThrows(EvaluationException.class, () -> undefinedExpression2.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("any(UNDEFINED, {testField == 10})", print(undefinedExpression2));
  }

  @Test
  void testArrayHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    ValueExpression<?> targetExpression = DSL.value(new Object[] {this, "hello"});

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(java.lang.Object[], {true})", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(java.lang.Object[], {false})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "testField"), value(10)));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(java.lang.Object[], {@it.testField == 10})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(java.lang.Object[], {@it == \"hello\"})", print(expression));
  }

  @Test
  void testListHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    ValueExpression<?> targetExpression = DSL.value(Arrays.asList(this, "hello"));

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(List, {true})", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(List, {false})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "testField"), value(10)));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(List, {@it.testField == 10})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(List, {@it == \"hello\"})", print(expression));
  }

  @Test
  void testMapHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", null);

    ValueExpression<?> targetExpression = DSL.value(valueMap);

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(Map, {true})", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(Map, {false})", print(expression));

    expression =
        any(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("b")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(Map, {@it.key == \"b\"})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("a")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(Map, {@it.value == \"a\"})", print(expression));

    expression =
        any(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("c")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(Map, {@it.key == \"c\"})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("c")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(Map, {@it.value == \"c\"})", print(expression));
  }

  @Test
  void testSetHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    Set<String> valueSet = new HashSet<>();
    valueSet.add("foo");
    valueSet.add("bar");

    ValueExpression<?> targetExpression = DSL.value(valueSet);
    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(Set, {true})", print(expression));

    targetExpression = DSL.value(valueSet);
    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(Set, {false})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(Set, {@it == \"foo\"})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("key")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(Set, {@it == \"key\"})", print(expression));
  }

  @Test
  void emptiness() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    HasAnyExpression expression = any(value(Collections.emptyList()), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(List, {true})", print(expression));

    expression = any(value(Collections.emptyMap()), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(Map, {true})", print(expression));

    expression = any(value(Collections.emptySet()), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("any(Set, {true})", print(expression));
  }

  @Test
  void keyValueMap() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", null);

    ValueExpression<?> targetExpression = DSL.value(valueMap);
    HasAnyExpression expression =
        any(targetExpression, eq(ref(ValueReferences.KEY_REF), value("b")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(Map, {@key == \"b\"})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.VALUE_REF), value("a")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("any(Map, {@value == \"a\"})", print(expression));
  }

  @Test
  void testUnsupportedList() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    ListValue collection = new ListValue(new CustomList());
    HasAnyExpression expression =
        any(collection, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(ctx));
    assertEquals(
        "Unsupported List class: com.datadog.debugger.el.expressions.HasAnyExpressionTest$CustomList",
        exception.getMessage());
    assertEquals("any(List, {@it == \"foo\"})", print(expression));
  }

  @Test
  void testUnsupportedMap() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    MapValue collection = new MapValue(new CustomMap());
    HasAnyExpression expression = any(collection, eq(ref(ValueReferences.VALUE_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(ctx));
    assertEquals(
        "Unsupported Map class: com.datadog.debugger.el.expressions.HasAnyExpressionTest$CustomMap",
        exception.getMessage());
    assertEquals("any(Map, {@value == \"foo\"})", print(expression));
  }

  @Test
  void testUnsupportedSet() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    SetValue collection = new SetValue(new CustomSet());
    HasAnyExpression expression =
        any(collection, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(ctx));
    assertEquals(
        "Unsupported Set class: com.datadog.debugger.el.expressions.HasAnyExpressionTest$CustomSet",
        exception.getMessage());
    assertEquals("any(Set, {@it == \"foo\"})", print(expression));
  }

  static class CustomList extends java.util.ArrayList<String> {}

  static class CustomMap extends HashMap<String, Integer> {}

  static class CustomSet extends java.util.HashSet<String> {}
}
