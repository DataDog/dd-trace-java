package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.StaticValueRefResolver;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValueRefExpressionTest {

  private ValueReferenceResolver ctx;

  @BeforeEach
  void setup() {
    ctx = StaticValueRefResolver.self(ValueRefExpressionTest.this);
  }

  @Test
  void testRef() {
    ValueRefExpression valueRef = new ValueRefExpression("b");
    ExTester instance = new ExTester(null, "\"hello\"");
    Value<?> val = valueRef.evaluate(StaticValueRefResolver.self(instance));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals(instance.getB(), val.getValue());
  }

  @Test
  void testPredicatedRef() {
    ValueRefExpression valueRef = new ValueRefExpression("b");
    ValueRefExpression invalidValueRef = new ValueRefExpression("x");

    IsEmptyExpression isEmpty = new IsEmptyExpression(valueRef);
    IsEmptyExpression isEmptyInvalid = new IsEmptyExpression(invalidValueRef);

    ExTester instance = new ExTester(null, "\"hello\"");
    ValueReferenceResolver ctx = StaticValueRefResolver.self(instance);

    assertFalse(isEmpty.evaluate(ctx).test());

    assertTrue(isEmptyInvalid.evaluate(ctx).test());
    assertFalse(and(isEmptyInvalid, isEmpty).evaluate(ctx).test());
    assertTrue(or(isEmptyInvalid, isEmpty).evaluate(ctx).test());
  }

  @Test
  void contextRef() {
    ExTester instance = new ExTester(null, "hello");
    long limit = 511L;
    String msg = "Hello there";
    int i = 6;

    String limitArg = "limit";
    String msgArg = "msg";
    String iVar = "i";

    Map<String, Object> values = new HashMap<>();
    values.put(limitArg, limit);
    values.put(msgArg, msg);
    values.put(iVar, i);

    long duration = TimeUnit.NANOSECONDS.convert(680, TimeUnit.MILLISECONDS);
    boolean returnVal = true;
    ValueReferenceResolver resolver =
        new StaticValueRefResolver(instance, duration, returnVal, values);

    Assertions.assertEquals(
        duration, DSL.ref(ValueReferences.DURATION_REF).evaluate(resolver).getValue());
    assertEquals(returnVal, DSL.ref(ValueReferences.RETURN_REF).evaluate(resolver).getValue());
    assertEquals(limit, DSL.ref(limitArg).evaluate(resolver).getValue());
    assertEquals(msg, DSL.ref(msgArg).evaluate(resolver).getValue());
    assertEquals(
        (long) i, DSL.ref(iVar).evaluate(resolver).getValue()); // int value is widened to long
    assertEquals(
        Values.UNDEFINED_OBJECT,
        DSL.ref(ValueReferences.synthetic("invalid")).evaluate(resolver).getValue());
  }
}
