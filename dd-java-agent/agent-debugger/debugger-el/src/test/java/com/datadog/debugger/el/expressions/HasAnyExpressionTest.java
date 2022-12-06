package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.StaticValueRefResolver;
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
    StaticValueRefResolver resolver = StaticValueRefResolver.self(this);
    assertFalse(new HasAnyExpression(null, null).evaluate(resolver).test());
    assertFalse(
        new HasAnyExpression(value(Values.UNDEFINED_OBJECT), null).evaluate(resolver).test());
    assertTrue(new HasAnyExpression(value(this), null).evaluate(resolver).test());
    assertTrue(
        new HasAnyExpression(value(Collections.singletonList(this)), null)
            .evaluate(resolver)
            .test());
    assertTrue(
        new HasAnyExpression(value(Collections.singletonMap(this, this)), null)
            .evaluate(resolver)
            .test());
  }

  @Test
  void testNullHasAny() {
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    HasAnyExpression expression = any(null, PredicateExpression.TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = any(null, PredicateExpression.FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = any(null, eq(ref(".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());
  }

  @Test
  void testUndefinedHasAny() {
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    HasAnyExpression expression = any(value(Values.UNDEFINED_OBJECT), TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = any(null, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = any(value(Values.UNDEFINED_OBJECT), eq(ref(".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());
  }

  @Test
  void testSingleElementHasAny() {
    ValueReferenceResolver ctx = new StaticValueRefResolver(this, Long.MAX_VALUE, null, null);
    ValueExpression<?> targetExpression = new ObjectValue(this);
    HasAnyExpression expression = any(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = any(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression =
        any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());
  }

  @Test
  void testArrayHasAny() {
    ValueReferenceResolver ctx = new StaticValueRefResolver(this, Long.MAX_VALUE, null, null);
    ValueExpression<?> targetExpression = DSL.value(new Object[] {this, "hello"});

    HasAnyExpression expression = any(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = any(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression =
        any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());
  }

  @Test
  void testListHasAny() {
    ValueReferenceResolver ctx = new StaticValueRefResolver(this, Long.MAX_VALUE, null, null);
    ValueExpression<?> targetExpression = DSL.value(Arrays.asList(this, "hello"));

    HasAnyExpression expression = any(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = any(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression =
        any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());
  }

  @Test
  void testMapHasAny() {
    ValueReferenceResolver ctx = new StaticValueRefResolver(this, Long.MAX_VALUE, null, null);
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", null);

    ValueExpression<?> targetExpression = DSL.value(valueMap);

    HasAnyExpression expression = any(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = any(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".key"), value("b")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression =
        any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".value"), value("a")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".key"), value("c")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression =
        any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".value"), value("c")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());
  }
}
