package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ValueRefExpressionTest {

  @Test
  void testRef() {
    ValueRefExpression valueRef = new ValueRefExpression("b");
    ExObjectWithRefAndValue instance = new ExObjectWithRefAndValue(null, "hello");
    Value<?> val = valueRef.evaluate(RefResolverHelper.createResolver(instance));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals(instance.getB(), val.getValue());
    assertEquals("b", print(valueRef));
  }

  @Test
  void testPredicatedRef() {
    ValueRefExpression valueRef = new ValueRefExpression("b");
    ValueRefExpression invalidValueRef = new ValueRefExpression("x");

    IsEmptyExpression isEmpty = new IsEmptyExpression(valueRef);
    IsEmptyExpression isEmptyInvalid = new IsEmptyExpression(invalidValueRef);

    ExObjectWithRefAndValue instance = new ExObjectWithRefAndValue(null, "hello");
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(instance);

    assertFalse(isEmpty.evaluate(ctx));
    assertEquals("isEmpty(b)", print(isEmpty));

    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, () -> isEmptyInvalid.evaluate(ctx));
    assertEquals("Cannot find symbol: x", runtimeException.getMessage());
    runtimeException =
        assertThrows(RuntimeException.class, () -> and(isEmptyInvalid, isEmpty).evaluate(ctx));
    assertEquals("Cannot find symbol: x", runtimeException.getMessage());
    runtimeException =
        assertThrows(RuntimeException.class, () -> or(isEmptyInvalid, isEmpty).evaluate(ctx));
    assertEquals("Cannot find symbol: x", runtimeException.getMessage());
    assertEquals("isEmpty(x)", print(isEmptyInvalid));
  }

  @Test
  void contextRef() {
    ExObjectWithRefAndValue instance = new ExObjectWithRefAndValue(null, "hello");
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
    Map<String, Object> exts = new HashMap<>();
    exts.put(ValueReferences.RETURN_EXTENSION_NAME, returnVal);
    exts.put(ValueReferences.DURATION_EXTENSION_NAME, duration);
    ValueReferenceResolver resolver =
        RefResolverHelper.createResolver(null, null, values).withExtensions(exts);

    ValueRefExpression expression = DSL.ref(ValueReferences.DURATION_REF);
    assertEquals(duration, expression.evaluate(resolver).getValue());
    assertEquals("@duration", print(expression));
    expression = DSL.ref(ValueReferences.RETURN_REF);
    assertEquals(returnVal, expression.evaluate(resolver).getValue());
    assertEquals("@return", print(expression));
    expression = DSL.ref(limitArg);
    assertEquals(limit, expression.evaluate(resolver).getValue());
    assertEquals("limit", print(expression));
    expression = DSL.ref(msgArg);
    assertEquals(msg, expression.evaluate(resolver).getValue());
    assertEquals("msg", print(expression));
    expression = DSL.ref(iVar);
    assertEquals(
        (long) i, expression.evaluate(resolver).getValue()); // int value is widened to long
    assertEquals("i", print(expression));
    ValueRefExpression invalidExpression = ref(ValueReferences.synthetic("invalid"));
    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, () -> invalidExpression.evaluate(resolver).getValue());
    assertEquals("Cannot find synthetic var: invalid", runtimeException.getMessage());
    assertEquals("@invalid", print(invalidExpression));
  }
}
