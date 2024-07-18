package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.ObjectValue;
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
    assertEquals("hasAny(null, true)", print(nullExpression));
    HasAnyExpression undefinedExpression =
        new HasAnyExpression(value(Values.UNDEFINED_OBJECT), null);
    exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("hasAny(UNDEFINED, true)", print(undefinedExpression));
    HasAnyExpression expression = new HasAnyExpression(value(this), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals(
        "hasAny(com.datadog.debugger.el.expressions.HasAnyExpressionTest, true)",
        print(expression));
    expression = new HasAnyExpression(value(Collections.singletonList(this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("hasAny(List, true)", print(expression));
    expression = new HasAnyExpression(value(Collections.singletonMap(this, this)), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("hasAny(Map, true)", print(expression));
  }

  @Test
  void testNullHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAnyExpression nullExpression1 = any(null, BooleanExpression.TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression1.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("hasAny(null, true)", print(nullExpression1));

    HasAnyExpression nullExpression2 = any(null, BooleanExpression.FALSE);
    exception = assertThrows(EvaluationException.class, () -> nullExpression2.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("hasAny(null, false)", print(nullExpression2));

    HasAnyExpression nullExpression3 = any(null, eq(ref("testField"), value(10)));
    exception = assertThrows(EvaluationException.class, () -> nullExpression3.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("hasAny(null, testField == 10)", print(nullExpression3));
  }

  @Test
  void testUndefinedHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAnyExpression undefinedExpression = any(value(Values.UNDEFINED_OBJECT), TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("hasAny(UNDEFINED, true)", print(undefinedExpression));

    HasAnyExpression nullExpression = any(null, FALSE);
    exception = assertThrows(EvaluationException.class, () -> nullExpression.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("hasAny(null, false)", print(nullExpression));

    HasAnyExpression undefinedExpression2 =
        any(value(Values.UNDEFINED_OBJECT), eq(ref("testField"), value(10)));
    exception = assertThrows(EvaluationException.class, () -> undefinedExpression2.evaluate(ctx));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("hasAny(UNDEFINED, testField == 10)", print(undefinedExpression2));
  }

  @Test
  void testSingleElementHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    ValueExpression<?> targetExpression = new ObjectValue(this);
    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals(
        "hasAny(com.datadog.debugger.el.expressions.HasAnyExpressionTest, true)",
        print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals(
        "hasAny(com.datadog.debugger.el.expressions.HasAnyExpressionTest, false)",
        print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "testField"), value(10)));
    assertTrue(expression.evaluate(ctx));
    assertEquals(
        "hasAny(com.datadog.debugger.el.expressions.HasAnyExpressionTest, @it.testField == 10)",
        print(expression));
  }

  @Test
  void testArrayHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    ValueExpression<?> targetExpression = DSL.value(new Object[] {this, "hello"});

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(java.lang.Object[], true)", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(java.lang.Object[], false)", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "testField"), value(10)));
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(java.lang.Object[], @it.testField == 10)", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(java.lang.Object[], @it == \"hello\")", print(expression));
  }

  @Test
  void testListHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    ValueExpression<?> targetExpression = DSL.value(Arrays.asList(this, "hello"));

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(List, true)", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(List, false)", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "testField"), value(10)));
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(List, @it.testField == 10)", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(List, @it == \"hello\")", print(expression));
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
    assertEquals("hasAny(Map, true)", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(Map, false)", print(expression));

    expression =
        any(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("b")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(Map, @it.key == \"b\")", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("a")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(Map, @it.value == \"a\")", print(expression));

    expression =
        any(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("c")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(Map, @it.key == \"c\")", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("c")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(Map, @it.value == \"c\")", print(expression));
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
    assertEquals("hasAny(Set, true)", print(expression));

    targetExpression = DSL.value(valueSet);
    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(Set, false)", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    assertTrue(expression.evaluate(ctx));
    assertEquals("hasAny(Set, @it == \"foo\")", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("key")));
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(Set, @it == \"key\")", print(expression));
  }

  @Test
  void emptiness() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null);
    HasAnyExpression expression = any(value(Collections.emptyList()), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(List, true)", print(expression));

    expression = any(value(Collections.emptyMap()), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(Map, true)", print(expression));

    expression = any(value(Collections.emptySet()), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(Set, true)", print(expression));
  }
}
