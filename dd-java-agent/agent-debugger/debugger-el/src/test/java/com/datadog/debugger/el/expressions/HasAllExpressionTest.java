package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.StaticValueRefResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HasAllExpressionTest {
  private final int testField = 10;

  @Test
  void testNullPredicate() {
    StaticValueRefResolver resolver = StaticValueRefResolver.self(this);
    assertFalse(new HasAllExpression(null, null).evaluate(resolver).test());
    assertFalse(
        new HasAllExpression(value(Values.UNDEFINED_OBJECT), null).evaluate(resolver).test());
    assertTrue(new HasAllExpression(value(this), null).evaluate(resolver).test());
    assertTrue(
        new HasAllExpression(value(Collections.singletonList(this)), null)
            .evaluate(resolver)
            .test());
    assertTrue(
        new HasAllExpression(value(Collections.singletonMap(this, this)), null)
            .evaluate(resolver)
            .test());
  }

  @Test
  void testNullHasAll() {
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    HasAllExpression expression = all(null, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(null, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(null, eq(ref(".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());
  }

  @Test
  void testUndefinedHasAll() {
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    HasAllExpression expression = all(value(Values.UNDEFINED_OBJECT), TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(null, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(value(Values.UNDEFINED_OBJECT), eq(ref(".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());
  }

  @Test
  void testSingleElementHasAll() {
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    ValueExpression<?> targetExpression = value(this);
    HasAllExpression expression = all(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = all(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(targetExpression, eq(ref(".testField"), value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());
  }

  @Test
  void testArrayHasAll() {
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    ValueExpression<?> targetExpression = value(new Object[] {this, "hello"});

    HasAllExpression expression = all(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = all(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    ValueRefExpression fldRef = ref(ValueReferences.ITERATOR_REF + ".testField");
    ValueRefExpression itRef = ref(ValueReferences.ITERATOR_REF);

    expression = all(targetExpression, eq(fldRef, value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(targetExpression, eq(itRef, value("hello")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(targetExpression, not(isEmpty(itRef)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());
  }

  @Test
  void testListHasAll() {
    ValueReferenceResolver ctx = StaticValueRefResolver.self(this);
    ValueExpression<?> targetExpression = value(Arrays.asList(this, "hello"));

    HasAllExpression expression = all(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = all(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    ValueRefExpression fldRef = ref(ValueReferences.ITERATOR_REF + ".testField");
    ValueRefExpression itRef = ref(ValueReferences.ITERATOR_REF);

    expression = all(targetExpression, eq(fldRef, value(10)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(targetExpression, eq(itRef, value("hello")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(targetExpression, not(isEmpty(itRef)));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());
  }

  @Test
  void testMapHasAny() {
    ValueReferenceResolver ctx = new StaticValueRefResolver(this, Long.MAX_VALUE, null, null);
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", "a");

    ValueExpression<?> targetExpression = value(valueMap);

    HasAllExpression expression = all(targetExpression, TRUE);
    Predicate predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());

    expression = all(targetExpression, FALSE);
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression = all(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".key"), value("a")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertFalse(predicate.test());

    expression =
        all(targetExpression, eq(ref(ValueReferences.ITERATOR_REF + ".value"), value("a")));
    predicate = expression.evaluate(ctx);
    assertNotNull(predicate);
    assertTrue(predicate.test());
  }
}
