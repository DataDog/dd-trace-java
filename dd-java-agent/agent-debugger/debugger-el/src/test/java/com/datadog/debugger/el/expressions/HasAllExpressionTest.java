package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class HasAllExpressionTest {
  private final int testField = 10;

  @Test
  void testNullPredicate() {
    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    HasAllExpression nullExpression = new HasAllExpression(null, null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("all(null, {true})", print(nullExpression));
    HasAllExpression undefinedExpression =
        new HasAllExpression(value(Values.UNDEFINED_OBJECT), null);
    exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("all(UNDEFINED, {true})", print(undefinedExpression));
    HasAllExpression expression = new HasAllExpression(value(new Object[] {this}), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("all(java.lang.Object[], {true})", print(expression));
    expression = new HasAllExpression(value(Collections.singletonList(this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("all(List, {true})", print(expression));
    expression = new HasAllExpression(value(Collections.singletonMap(this, this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("all(Map, {true})", print(expression));
  }

  @Test
  void testNullHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAllExpression nullExpression1 = all(null, TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression1.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("all(null, {true})", print(nullExpression1));

    HasAllExpression nullExpression2 = all(null, FALSE);
    exception = assertThrows(EvaluationException.class, () -> nullExpression2.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("all(null, {false})", print(nullExpression2));

    HasAllExpression nullExpression3 = all(null, eq(ref("testField"), value(10)));
    exception = assertThrows(EvaluationException.class, () -> nullExpression3.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("all(null, {testField == 10})", print(nullExpression3));
  }

  @Test
  void testUndefinedHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAllExpression undefinedExpression = all(value(Values.UNDEFINED_OBJECT), TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("all(UNDEFINED, {true})", print(undefinedExpression));

    HasAllExpression nullExpression = all(null, FALSE);
    exception = assertThrows(EvaluationException.class, () -> nullExpression.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("all(null, {false})", print(nullExpression));

    HasAllExpression expression =
        all(value(Values.UNDEFINED_OBJECT), eq(ref("testField"), value(10)));
    exception = assertThrows(EvaluationException.class, () -> expression.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("all(UNDEFINED, {testField == 10})", print(expression));
  }

  @Test
  void testArrayHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    ValueExpression<?> targetExpression = value(new Object[] {this, "hello"});

    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(java.lang.Object[], {true})", print(expression));

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(java.lang.Object[], {false})", print(expression));

    GetMemberExpression fldRef = getMember(ref(ValueReferences.ITERATOR_REF), "testField");
    ValueRefExpression itRef = ref(ValueReferences.ITERATOR_REF);

    RuntimeException runtimeException =
        assertThrows(
            RuntimeException.class,
            () -> all(targetExpression, eq(fldRef, value(10))).evaluate(ctx));
    assertEquals("Cannot dereference field: testField", runtimeException.getMessage());

    expression = all(targetExpression, eq(itRef, value("hello")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(java.lang.Object[], {@it == \"hello\"})", print(expression));

    expression = all(targetExpression, not(isEmpty(itRef)));
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(java.lang.Object[], {not(isEmpty(@it))})", print(expression));
  }

  @Test
  void testListHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    ValueExpression<?> targetExpression = value(Arrays.asList(this, "hello"));

    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(List, {true})", print(expression));

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(List, {false})", print(expression));

    ValueRefExpression fldRef = ref(ValueReferences.ITERATOR_REF + "testField");
    ValueRefExpression itRef = ref(ValueReferences.ITERATOR_REF);

    RuntimeException runtimeException =
        assertThrows(
            RuntimeException.class,
            () -> all(targetExpression, eq(fldRef, value(10))).evaluate(ctx));
    assertEquals("Cannot find synthetic var: ittestField", runtimeException.getMessage());

    expression = all(targetExpression, eq(itRef, value("hello")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(List, {@it == \"hello\"})", print(expression));

    expression = all(targetExpression, not(isEmpty(itRef)));
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(List, {not(isEmpty(@it))})", print(expression));
  }

  @Test
  void testMapHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", "a");

    ValueExpression<?> targetExpression = value(valueMap);

    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(Map, {true})", print(expression));

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(Map, {false})", print(expression));

    expression =
        all(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("a")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(Map, {@it.key == \"a\"})", print(expression));

    expression =
        all(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("a")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(Map, {@it.value == \"a\"})", print(expression));
  }

  @Test
  void testSetHasAll() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    Set<String> valueSet = new HashSet<>();
    valueSet.add("foo");
    valueSet.add("bar");

    ValueExpression<?> targetExpression = value(valueSet);

    HasAllExpression expression = all(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(Set, {true})", print(expression));

    expression = all(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(Set, {false})", print(expression));

    expression = all(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("key")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(Set, {@it == \"key\"})", print(expression));

    expression =
        all(
            targetExpression,
            or(
                eq(ref(ValueReferences.ITERATOR_REF), value("foo")),
                eq(ref(ValueReferences.ITERATOR_REF), value("bar"))));
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(Set, {@it == \"foo\" || @it == \"bar\"})", print(expression));
  }

  @Test
  void emptiness() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    HasAllExpression expression = all(value(new String[] {}), TRUE);
    assertTrue(expression.evaluate(ctx));
    expression = all(value(Collections.emptyList()), TRUE);
    assertTrue(expression.evaluate(ctx));
    expression = all(value(Collections.emptyMap()), TRUE);
    assertTrue(expression.evaluate(ctx));
    expression = all(value(Collections.emptySet()), TRUE);
    assertTrue(expression.evaluate(ctx));
  }

  @Test
  void keyValueMap() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", "a");
    ValueExpression<?> targetExpression = value(valueMap);
    HasAllExpression expression =
        all(targetExpression, eq(ref(ValueReferences.KEY_REF), value("a")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("all(Map, {@key == \"a\"})", print(expression));

    expression = all(targetExpression, eq(ref(ValueReferences.VALUE_REF), value("a")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("all(Map, {@value == \"a\"})", print(expression));
  }

  @Test
  void testUnsupportedList() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    ListValue collection = new ListValue(new CustomList());
    HasAllExpression expression =
        all(collection, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(ctx));
    assertEquals(
        "Unsupported List class: com.datadog.debugger.el.expressions.HasAllExpressionTest$CustomList",
        exception.getMessage());
    assertEquals("all(List, {@it == \"foo\"})", print(expression));
  }

  @Test
  void testUnsupportedMap() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    MapValue collection = new MapValue(new CustomMap());
    HasAllExpression expression = all(collection, eq(ref(ValueReferences.VALUE_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(ctx));
    assertEquals(
        "Unsupported Map class: com.datadog.debugger.el.expressions.HasAllExpressionTest$CustomMap",
        exception.getMessage());
    assertEquals("all(Map, {@value == \"foo\"})", print(expression));
  }

  @Test
  void testUnsupportedSet() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    SetValue collection = new SetValue(new CustomSet());
    HasAllExpression expression =
        all(collection, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(ctx));
    assertEquals(
        "Unsupported Set class: com.datadog.debugger.el.expressions.HasAllExpressionTest$CustomSet",
        exception.getMessage());
    assertEquals("all(Set, {@it == \"foo\"})", print(expression));
  }

  static class CustomList extends java.util.ArrayList<String> {}

  static class CustomMap extends HashMap<String, Integer> {}

  static class CustomSet extends java.util.HashSet<String> {}
}
