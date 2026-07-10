package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.EvalContextHelper.createEvalContext;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.Values;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class ContainsExpressionTest {
  private final EvalContext evalContext = createEvalContext(this);

  private List<String> list = Arrays.asList("foo", "bar", "baz");
  private List<String> listWithNull = Arrays.asList("foo", null, "baz");
  private List<Integer> largeList = new ArrayList<>();

  {
    for (int i = 0; i < 1_000_001; i++) {
      largeList.add(i);
    }
  }

  private List<String> slowList = new ArrayList<>();

  {
    for (int i = 0; i < 10_000; i++) {
      slowList.add(String.valueOf(i));
    }
  }

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
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("contains(null, null)", print(expression));
  }

  @Test
  void undefinedExpression() {
    ContainsExpression expression =
        new ContainsExpression(DSL.value(Values.UNDEFINED_OBJECT), new StringValue(null));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("contains(UNDEFINED, \"null\")", print(expression));
  }

  @Test
  void stringExpression() {
    ContainsExpression expression =
        new ContainsExpression(DSL.value("abcd"), new StringValue("bc"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(\"abcd\", \"bc\")", print(expression));

    expression = new ContainsExpression(DSL.value("abc"), new StringValue("dc"));
    assertFalse(expression.evaluate(evalContext));
    assertEquals("contains(\"abc\", \"dc\")", print(expression));

    ContainsExpression nullValExpression = new ContainsExpression(DSL.value("abcd"), null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullValExpression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for non-string value", exception.getMessage());
    assertEquals("contains(\"abcd\", null)", print(nullValExpression));
  }

  @Test
  void listExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("list"), DSL.value("bar"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(list, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("listWithNull"), null);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(listWithNull, null)", print(expression));

    ContainsExpression largeExpression = new ContainsExpression(DSL.ref("largeList"), DSL.value(1));
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> largeExpression.evaluate(evalContext));
    assertEquals("Collection too large (>1000000)", evaluationException.getMessage());

    ContainsExpression slowListExpression =
        new ContainsExpression(DSL.ref("slowList"), DSL.value(new SlowObject()));
    assertThrows(
        EvaluationException.class,
        () -> slowListExpression.evaluate(createEvalContext(this, Duration.ofMillis(1))));
  }

  @Test
  void arrayExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("arrayStr"), DSL.value("bar"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(arrayStr, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayInt"), DSL.value(2));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(arrayInt, 2)", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayChar"), DSL.value("b"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(arrayChar, \"b\")", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayLong"), DSL.value(2));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(arrayLong, 2)", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayDouble"), DSL.value(2.0));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(arrayDouble, 2.0)", print(expression));

    expression = new ContainsExpression(DSL.ref("arrayStrWithNull"), null);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(arrayStrWithNull, null)", print(expression));

    ContainsExpression primitiveNullExpression = new ContainsExpression(DSL.ref("arrayInt"), null);
    EvaluationException exception =
        assertThrows(
            EvaluationException.class, () -> primitiveNullExpression.evaluate(evalContext));
    assertEquals("Cannot compare null with primitive array", exception.getMessage());
    assertEquals("contains(arrayInt, null)", print(primitiveNullExpression));
  }

  @Test
  void mapExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("mapStr"), DSL.value("bar"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(mapStr, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("mapStrWithNull"), null);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(mapStrWithNull, null)", print(expression));
  }

  @Test
  void setExpression() {
    ContainsExpression expression = new ContainsExpression(DSL.ref("setStr"), DSL.value("bar"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(setStr, \"bar\")", print(expression));

    expression = new ContainsExpression(DSL.ref("setStrWithNull"), null);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("contains(setStrWithNull, null)", print(expression));
  }

  static class SlowObject {
    @Override
    public boolean equals(Object obj) {
      LockSupport.parkNanos(1000);
      return super.equals(obj);
    }
  }
}
