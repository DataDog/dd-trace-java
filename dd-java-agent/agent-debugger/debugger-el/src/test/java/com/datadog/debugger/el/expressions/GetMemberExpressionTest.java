package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static com.datadog.debugger.el.TestHelper.setFieldInConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.debugger.el.RedactedException;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.Value;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetMemberExpressionTest {

  @Test
  void getMemberLevel1() {
    GetMemberExpression expr = new GetMemberExpression(new ValueRefExpression("ref"), "b");
    ObjectWithRefAndValue parent = new ObjectWithRefAndValue(null, "hello");
    ExObjectWithRefAndValue instance = new ExObjectWithRefAndValue(parent, "world");
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(instance));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals(parent.getB(), val.getValue());
    assertEquals("ref.b", print(expr));
  }

  @Test
  void getMemberLevel2() {
    // ref.ref.b
    GetMemberExpression expr =
        new GetMemberExpression(new GetMemberExpression(new ValueRefExpression("ref"), "ref"), "b");
    ObjectWithRefAndValue root = new ObjectWithRefAndValue(null, "hello");
    ObjectWithRefAndValue parent = new ObjectWithRefAndValue(root, "");
    ExObjectWithRefAndValue instance = new ExObjectWithRefAndValue(parent, "world");
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(instance));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals(root.getB(), val.getValue());
    assertEquals("ref.ref.b", print(expr));
  }

  @Test
  void getMemberUndefined() {
    GetMemberExpression expr = new GetMemberExpression(ValueExpression.UNDEFINED, "size");
    assertEquals(Value.undefined(), expr.evaluate(RefResolverHelper.createResolver(this)));
  }

  class StoreSecret {
    String password;
    String str;

    public StoreSecret(String password) {
      this.password = password;
      this.str = password;
    }
  }

  class Holder {
    StoreSecret store;

    public Holder(StoreSecret secret) {
      this.store = secret;
    }
  }

  @Test
  void redacted() {
    GetMemberExpression expr = new GetMemberExpression(new ValueRefExpression("store"), "password");
    Holder instance = new Holder(new StoreSecret("secret123"));
    RedactedException redactedException =
        assertThrows(
            RedactedException.class,
            () -> expr.evaluate(RefResolverHelper.createResolver(instance)));
    assertEquals(
        "Could not evaluate the expression because 'store.password' was redacted",
        redactedException.getMessage());
  }

  @Test
  void redactedType() {
    Config config = Config.get();
    setFieldInConfig(
        config,
        "dynamicInstrumentationRedactedTypes",
        "com.datadog.debugger.el.expressions.GetMemberExpressionTest*");
    try {
      Redaction.addUserDefinedTypes(Config.get());
      GetMemberExpression expr = new GetMemberExpression(new ValueRefExpression("store"), "str");
      Holder instance = new Holder(new StoreSecret("secret123"));
      RedactedException redactedException =
          assertThrows(
              RedactedException.class,
              () -> expr.evaluate(RefResolverHelper.createResolver(instance)));
      assertEquals(
          "Could not evaluate the expression because 'store' was redacted",
          redactedException.getMessage());
    } finally {
      Redaction.clearUserDefinedTypes();
    }
  }

  @Test
  void stringPrimitive() {
    class StrPrimitive {
      UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426655440000");
      Class<?> clazz = String.class;
    }
    class Holder {
      StrPrimitive strPrimitive = new StrPrimitive();
    }
    GetMemberExpression expr =
        new GetMemberExpression(new ValueRefExpression("strPrimitive"), "uuid");
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(new Holder()));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals("123e4567-e89b-12d3-a456-426655440000", val.getValue());
    assertEquals("strPrimitive.uuid", print(expr));
    expr = new GetMemberExpression(new ValueRefExpression("strPrimitive"), "clazz");
    val = expr.evaluate(RefResolverHelper.createResolver(new Holder()));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals("java.lang.String", val.getValue());
    assertEquals("strPrimitive.clazz", print(expr));
  }
}
