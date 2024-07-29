package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContainsExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);

  private List<String> list = Arrays.asList("foo", "bar", "baz");
  private List<String> listWithNull = Arrays.asList("foo", null, "baz");
  private String[] arrayStr = new String[] {"foo", "bar", "baz"};
  private String[] arrayStrWithNull = new String[] {"foo", null, "bar"};
  private int[] arrayInt = new int[] {1, 2, 3};
  private char[] arrayChar = new char[] {'a', 'b', 'c'};
  private long[] arrayLong = new long[] {1, 2, 3};
  private double[] arrayDouble = new double[] {1, 2, 3};
  private Map<String, String> mapStr = new HashMap<>();

  {
    mapStr.put("foo", "bar");
    mapStr.put("bar", "baz");
  }

  private Map<String, String> mapStrWithNull = new HashMap<>();

  {
    mapStrWithNull.put("foo", "bar");
    mapStrWithNull.put(null, "baz");
  }

  private Set<String> setStr = new HashSet<>();

  {
    setStr.add("foo");
    setStr.add("bar");
  }

  private Set<String> setStrWithNull = new HashSet<>();

  {
    setStrWithNull.add("foo");
    setStrWithNull.add(null);
  }

  @Test
  void nullExpression() {
    ContainsExpression expression = new ContainsExpression(null, null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("contains(null, null)", print(expression));
  }

  @Test
  void undefinedExpression() {
    ContainsExpression expression =
        new ContainsExpression(DSL.value(Values.UNDEFINED_OBJECT), new StringValue(null));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("contains(UNDEFINED, \"null\")", print(expression));
  }

  @Test
  void stringExpression() {
    ContainsExpression expression =
        new ContainsExpression(DSL.value("abcd"), new StringValue("bc"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(\"abcd\", \"bc\")", print(expression));

    expression = new ContainsExpression(DSL.value("abc"), new StringValue("dc"));
    assertFalse(expression.evaluate(resolver));
    assertEquals("contains(\"abc\", \"dc\")", print(expression));

    ContainsExpression nullValExpression = new ContainsExpression(DSL.value("abcd"), null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullValExpression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for non-string value", exception.getMessage());
    assertEquals("contains(\"abcd\", null)", print(nullValExpression));
  }

  @Test
  void listExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("list"), DSL.value("bar"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(list, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("listWithNull"), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(listWithNull, null)", print(expression));
  }

  @Test
  void arrayExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("arrayStr"), DSL.value("bar"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(arrayStr, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayInt"), DSL.value(2));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(arrayInt, 2)", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayChar"), DSL.value("b"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(arrayChar, \"b\")", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayLong"), DSL.value(2));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(arrayLong, 2)", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayDouble"), DSL.value(2.0));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(arrayDouble, 2.0)", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayStrWithNull"), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(arrayStrWithNull, null)", print(expression));

    ContainsExpression primitiveNullExpression = new ContainsExpression(DSL.ref("arrayInt"), null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> primitiveNullExpression.evaluate(resolver));
    assertEquals("Cannot compare null with primitive array", exception.getMessage());
    assertEquals("contains(arrayInt, null)", print(primitiveNullExpression));
  }

  @Test
  void mapExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("mapStr"), DSL.value("bar"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(mapStr, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("mapStrWithNull"), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(mapStrWithNull, null)", print(expression));
  }

  @Test
  void setExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("setStr"), DSL.value("bar"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(setStr, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("setStrWithNull"), null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("contains(setStrWithNull, null)", print(expression));
  }
}
