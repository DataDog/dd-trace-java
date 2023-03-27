package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.ObjectValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HasAnyExpressionTest {
  private final int testField = 10;

  @Test
  void testNullPredicate() {
    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    HasAnyExpression expression = new HasAnyExpression(null, null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("hasAny(null, true)", print(expression));
    expression = new HasAnyExpression(value(Values.UNDEFINED_OBJECT), null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("hasAny(UNDEFINED, true)", print(expression));
    expression = new HasAnyExpression(value(this), null);
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
    HasAnyExpression expression = any(null, BooleanExpression.TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(null, true)", print(expression));

    expression = any(null, BooleanExpression.FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(null, false)", print(expression));

    expression = any(null, eq(ref("testField"), value(10)));
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(null, testField == 10)", print(expression));
  }

  @Test
  void testUndefinedHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(this);
    HasAnyExpression expression = any(value(Values.UNDEFINED_OBJECT), TRUE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(UNDEFINED, true)", print(expression));

    expression = any(null, FALSE);
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(null, false)", print(expression));

    expression = any(value(Values.UNDEFINED_OBJECT), eq(ref("testField"), value(10)));
    assertFalse(expression.evaluate(ctx));
    assertEquals("hasAny(UNDEFINED, testField == 10)", print(expression));
  }

  @Test
  void testSingleElementHasAny() {
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null, null);
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
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null, null);
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
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null, null);
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
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null, null);
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
}
