package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static com.datadog.debugger.el.TestHelper.setFieldInConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RedactedException;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.Value;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext.CapturedValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
    assertEquals("Cannot dereference field: x", runtimeException.getMessage());
    runtimeException =
        assertThrows(RuntimeException.class, () -> and(isEmptyInvalid, isEmpty).evaluate(ctx));
    assertEquals("Cannot dereference field: x", runtimeException.getMessage());
    runtimeException =
        assertThrows(RuntimeException.class, () -> or(isEmptyInvalid, isEmpty).evaluate(ctx));
    assertEquals("Cannot dereference field: x", runtimeException.getMessage());
    assertEquals("isEmpty(x)", print(isEmptyInvalid));
  }

  @Test
  void contextRef() {
    class Obj {
      long limit = 511L;
      String msg = "Hello there";
      int i = 6;
    }

    long duration = TimeUnit.NANOSECONDS.convert(680, TimeUnit.MILLISECONDS);
    boolean returnVal = true;
    Throwable exception = new RuntimeException("oops");
    Map<String, CapturedValue> exts = new HashMap<>();
    exts.put(ValueReferences.RETURN_EXTENSION_NAME, CapturedValue.of(returnVal));
    exts.put(ValueReferences.DURATION_EXTENSION_NAME, CapturedValue.of(duration));
    exts.put(ValueReferences.EXCEPTION_EXTENSION_NAME, CapturedValue.of(exception));
    ValueReferenceResolver resolver =
        RefResolverHelper.createResolver(new Obj()).withExtensions(exts);

    ValueRefExpression expression = DSL.ref(ValueReferences.DURATION_REF);
    assertEquals(duration, expression.evaluate(resolver).getValue());
    assertEquals("@duration", print(expression));
    expression = DSL.ref(ValueReferences.RETURN_REF);
    assertEquals(returnVal, expression.evaluate(resolver).getValue());
    assertEquals("@return", print(expression));
    expression = DSL.ref(ValueReferences.EXCEPTION_REF);
    assertEquals(exception, expression.evaluate(resolver).getValue());
    assertEquals("@exception", print(expression));
    expression = DSL.ref("limit");
    assertEquals(511L, expression.evaluate(resolver).getValue());
    assertEquals("limit", print(expression));
    expression = DSL.ref("msg");
    assertEquals("Hello there", expression.evaluate(resolver).getValue());
    assertEquals("msg", print(expression));
    expression = DSL.ref("i");
    assertEquals(6, expression.evaluate(resolver).getValue()); // int value is widened to long
    assertEquals("i", print(expression));
    ValueRefExpression invalidExpression = ref(ValueReferences.synthetic("invalid"));
    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, () -> invalidExpression.evaluate(resolver).getValue());
    assertEquals("Cannot find synthetic var: invalid", runtimeException.getMessage());
    assertEquals("@invalid", print(invalidExpression));
  }

  class StoreSecret {
    String password;

    public StoreSecret(String password) {
      this.password = password;
    }
  }

  @Test
  public void redacted() {
    ValueRefExpression valueRef = new ValueRefExpression("password");
    StoreSecret instance = new StoreSecret("secret123");
    RedactedException redactedException =
        assertThrows(
            RedactedException.class,
            () -> valueRef.evaluate(RefResolverHelper.createResolver(instance)));
    assertEquals(
        "Could not evaluate the expression because 'password' was redacted",
        redactedException.getMessage());
  }

  @Test
  public void redactedType() {
    Config config = Config.get();
    setFieldInConfig(
        config, "dynamicInstrumentationRedactedTypes", "com.datadog.debugger.el.expressions.*");
    try {
      Redaction.addUserDefinedTypes(Config.get());
      ValueRefExpression valueRef = new ValueRefExpression("store");
      class Holder {
        StoreSecret store = new StoreSecret("secret123");
      }
      RedactedException redactedException =
          assertThrows(
              RedactedException.class,
              () -> valueRef.evaluate(RefResolverHelper.createResolver(new Holder())));
      assertEquals(
          "Could not evaluate the expression because 'store' was redacted",
          redactedException.getMessage());
    } finally {
      Redaction.clearUserDefinedTypes();
    }
  }

  @Test
  public void stringPrimitive() {
    class StrPrimitive {
      UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426655440000");
      Class<?> clazz = String.class;
    }
    ValueRefExpression valueRef = new ValueRefExpression("uuid");
    Value<?> val = valueRef.evaluate(RefResolverHelper.createResolver(new StrPrimitive()));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals("123e4567-e89b-12d3-a456-426655440000", val.getValue());
    assertEquals("uuid", print(valueRef));
    valueRef = new ValueRefExpression("clazz");
    val = valueRef.evaluate(RefResolverHelper.createResolver(new StrPrimitive()));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals("java.lang.String", val.getValue());
    assertEquals("clazz", print(valueRef));
  }
}
