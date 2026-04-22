package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static com.datadog.debugger.el.TestHelper.setFieldInConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.RedactedException;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.SetValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IndexExpressionTest {

  static final String UUID_1 = "123e4567-e89b-12d3-a456-426655440000";
  static final String UUID_2 = "123e4567-e89b-12d3-a456-426655440001";
  String[] strArray = new String[] {"foo", "bar", "baz"};
  UUID[] uuidArray = new UUID[] {UUID.fromString(UUID_1), UUID.fromString(UUID_2)};
  Object[] secretArray = new Object[] {new StoreSecret("secret123")};
  List<String> strList = new ArrayList<>(Arrays.asList(strArray));
  List<UUID> uuidList = new ArrayList<>(Arrays.asList(uuidArray));
  List<Object> secretList = new ArrayList<>(Arrays.asList(secretArray));
  Map<String, String> strMap = new HashMap<>();
  Map<String, UUID> uuidMap = new HashMap<>();
  Map<String, Object> secretMap = new HashMap<>();
  String str = "password";

  {
    strMap.put("foo", "bar");
    uuidMap.put("foo", UUID.fromString(UUID_1));
    secretMap.put("foo", new StoreSecret("secret123"));
  }

  @Test
  void testArray() {
    IndexExpression expr =
        new IndexExpression(new ValueRefExpression("strArray"), new NumericValue(1, ValueType.INT));
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(this));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals("bar", val.getValue());
    assertEquals("strArray[1]", print(expr));
  }

  @Test
  void testList() {
    IndexExpression expr =
        new IndexExpression(new ValueRefExpression("strList"), new NumericValue(1, ValueType.INT));
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(this));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals("bar", val.getValue());
    assertEquals("strList[1]", print(expr));
  }

  @Test
  void testMap() {
    IndexExpression expr =
        new IndexExpression(new ValueRefExpression("strMap"), new StringValue("foo"));
    Value<?> val = expr.evaluate(RefResolverHelper.createResolver(this));
    assertNotNull(val);
    assertFalse(val.isUndefined());
    assertEquals("bar", val.getValue());
    assertEquals("strMap[\"foo\"]", print(expr));
  }

  @Test
  void testUnsupportedSet() {
    IndexExpression expr =
        new IndexExpression(new SetValue(new HashSet<>()), new StringValue("foo"));
    EvaluationException evaluationException =
        assertThrows(
            EvaluationException.class, () -> expr.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals(
        "Cannot evaluate the expression for unsupported type: com.datadog.debugger.el.values.SetValue",
        evaluationException.getMessage());
  }

  @Test
  void testUnsupportedList() {
    IndexExpression expr =
        new IndexExpression(
            new ListValue(new ArrayList<String>() {}), new NumericValue(0, ValueType.INT));
    EvaluationException evaluationException =
        assertThrows(
            EvaluationException.class, () -> expr.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals(
        "Unsupported List class: com.datadog.debugger.el.expressions.IndexExpressionTest$1",
        evaluationException.getMessage());
  }

  @Test
  void testOutOfBoundsList() {
    IndexExpression expr =
        new IndexExpression(
            new ListValue(new ArrayList<String>()), new NumericValue(42, ValueType.INT));
    EvaluationException evaluationException =
        assertThrows(
            EvaluationException.class, () -> expr.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals("index[42] out of bounds: [0-0]", evaluationException.getMessage());
  }

  @Test
  void testUnsupportedMap() {
    IndexExpression expr =
        new IndexExpression(new MapValue(new HashMap<String, String>() {}), new StringValue("foo"));
    EvaluationException evaluationException =
        assertThrows(
            EvaluationException.class, () -> expr.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals(
        "Unsupported Map class: com.datadog.debugger.el.expressions.IndexExpressionTest$2",
        evaluationException.getMessage());
  }

  @Test
  void undefined() {
    IndexExpression expr =
        new IndexExpression(ValueExpression.UNDEFINED, new NumericValue(1, ValueType.INT));
    EvaluationException exception =
        assertThrows(
            EvaluationException.class, () -> expr.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
  }

  @Test
  void redacted() {
    IndexExpression expr1 =
        new IndexExpression(new ValueRefExpression("strMap"), new StringValue("password"));
    RedactedException redactedException =
        assertThrows(
            RedactedException.class,
            () -> expr1.evaluate(RefResolverHelper.createResolver(this)).getValue());
    assertEquals(
        "Could not evaluate the expression because 'strMap[\"password\"]' was redacted",
        redactedException.getMessage());
    IndexExpression expr2 =
        new IndexExpression(new ValueRefExpression("strMap"), new ValueRefExpression("str"));
    redactedException =
        assertThrows(
            RedactedException.class,
            () -> expr2.evaluate(RefResolverHelper.createResolver(this)).getValue());
    assertEquals(
        "Could not evaluate the expression because 'strMap[str]' was redacted",
        redactedException.getMessage());
  }

  @Test
  void redactedType() {
    Config config = Config.get();
    setFieldInConfig(
        config,
        "dynamicInstrumentationRedactedTypes",
        "com.datadog.debugger.el.expressions.IndexExpressionTest*");
    try {
      Redaction.addUserDefinedTypes(Config.get());
      IndexExpression exprArray =
          new IndexExpression(
              new ValueRefExpression("secretArray"), new NumericValue(0, ValueType.INT));
      RedactedException redactedException =
          assertThrows(
              RedactedException.class,
              () -> exprArray.evaluate(RefResolverHelper.createResolver(this)).getValue());
      assertEquals(
          "Could not evaluate the expression because 'secretArray[0]' was redacted",
          redactedException.getMessage());
      IndexExpression exprList =
          new IndexExpression(
              new ValueRefExpression("secretList"), new NumericValue(0, ValueType.INT));
      redactedException =
          assertThrows(
              RedactedException.class,
              () -> exprList.evaluate(RefResolverHelper.createResolver(this)).getValue());
      assertEquals(
          "Could not evaluate the expression because 'secretList[0]' was redacted",
          redactedException.getMessage());
      IndexExpression exprMap =
          new IndexExpression(new ValueRefExpression("secretMap"), new StringValue("foo"));
      redactedException =
          assertThrows(
              RedactedException.class,
              () -> exprMap.evaluate(RefResolverHelper.createResolver(this)).getValue());
      assertEquals(
          "Could not evaluate the expression because 'secretMap[\"foo\"]' was redacted",
          redactedException.getMessage());
    } finally {
      Redaction.clearUserDefinedTypes();
    }
  }

  @Test
  void stringPrimitives() {
    IndexExpression expr =
        new IndexExpression(
            new ValueRefExpression("uuidArray"), new NumericValue(1, ValueType.INT));
    assertEquals(UUID_2, expr.evaluate(RefResolverHelper.createResolver(this)).getValue());
    expr =
        new IndexExpression(new ValueRefExpression("uuidList"), new NumericValue(1, ValueType.INT));
    assertEquals(UUID_2, expr.evaluate(RefResolverHelper.createResolver(this)).getValue());
    expr = new IndexExpression(new ValueRefExpression("uuidMap"), new StringValue("foo"));
    assertEquals(UUID_1, expr.evaluate(RefResolverHelper.createResolver(this)).getValue());
  }

  private static class StoreSecret {
    String password;

    public StoreSecret(String password) {
      this.password = password;
    }
  }
}
