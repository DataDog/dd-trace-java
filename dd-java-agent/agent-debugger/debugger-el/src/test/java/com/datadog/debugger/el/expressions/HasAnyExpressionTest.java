package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.DSL.value;
import static com.datadog.debugger.el.EvalContextHelper.createEvalContext;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.SetValue;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HasAnyExpressionTest {
  private final int testField = 10;
  EvalContext evalContext = createEvalContext(this);

  @Test
  void testNullPredicate() {
    HasAnyExpression nullExpression = new HasAnyExpression(null, null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {true})", print(nullExpression));
    HasAnyExpression undefinedExpression =
        new HasAnyExpression(value(Values.UNDEFINED_OBJECT), null);
    exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("any(UNDEFINED, {true})", print(undefinedExpression));
    HasAnyExpression expression = new HasAnyExpression(value(new Object[] {this}), null);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(java.lang.Object[], {true})", print(expression));
    expression = new HasAnyExpression(value(Collections.singletonList(this)), null);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(List, {true})", print(expression));
    expression = new HasAnyExpression(value(Collections.singletonMap(this, this)), null);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Map, {true})", print(expression));
  }

  @Test
  void testNullHasAny() {
    HasAnyExpression nullExpression1 = any(null, BooleanExpression.TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression1.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {true})", print(nullExpression1));

    HasAnyExpression nullExpression2 = any(null, BooleanExpression.FALSE);
    exception =
        assertThrows(EvaluationException.class, () -> nullExpression2.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {false})", print(nullExpression2));

    HasAnyExpression nullExpression3 = any(null, eq(ref("testField"), value(10)));
    exception =
        assertThrows(EvaluationException.class, () -> nullExpression3.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {testField == 10})", print(nullExpression3));
  }

  @Test
  void testUndefinedHasAny() {
    HasAnyExpression undefinedExpression = any(value(Values.UNDEFINED_OBJECT), TRUE);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("any(UNDEFINED, {true})", print(undefinedExpression));

    HasAnyExpression nullExpression = any(null, FALSE);
    exception = assertThrows(EvaluationException.class, () -> nullExpression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("any(null, {false})", print(nullExpression));

    HasAnyExpression undefinedExpression2 =
        any(value(Values.UNDEFINED_OBJECT), eq(ref("testField"), value(10)));
    exception =
        assertThrows(EvaluationException.class, () -> undefinedExpression2.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("any(UNDEFINED, {testField == 10})", print(undefinedExpression2));
  }

  @Test
  void testArrayHasAny() {
    ValueExpression<?> targetExpression = value(new Object[] {this, "hello"});

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(java.lang.Object[], {true})", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(java.lang.Object[], {false})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "testField"), value(10)));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(java.lang.Object[], {@it.testField == 10})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(java.lang.Object[], {@it == \"hello\"})", print(expression));
  }

  @Test
  void testListHasAny() {
    ValueExpression<?> targetExpression = value(Arrays.asList(this, "hello"));

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(List, {true})", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(List, {false})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "testField"), value(10)));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(List, {@it.testField == 10})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("hello")));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(List, {@it == \"hello\"})", print(expression));
  }

  @Test
  void testLargeListHasAny() {
    List<Integer> largeList = new ArrayList<>();
    for (int i = 0; i < 1_000_000; i++) {
      largeList.add(i);
    }
    ValueExpression<?> targetExpression = value(largeList);

    HasAnyExpression expression = any(targetExpression, FALSE);
    EvalContext timeoutEvalContext = createEvalContext(this, Duration.ofMillis(1));
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression.evaluate(timeoutEvalContext));
    assertEquals("timeout (1ms)", evaluationException.getMessage());
    assertEquals("any(List, {false})", print(expression));
  }

  @Test
  void testMapHasAny() {
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", null);

    ValueExpression<?> targetExpression = value(valueMap);

    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Map, {true})", print(expression));

    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(Map, {false})", print(expression));

    expression =
        any(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("b")));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Map, {@it.key == \"b\"})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("a")));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Map, {@it.value == \"a\"})", print(expression));

    expression =
        any(targetExpression, eq(getMember(ref(ValueReferences.ITERATOR_REF), "key"), value("c")));
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(Map, {@it.key == \"c\"})", print(expression));

    expression =
        any(
            targetExpression,
            eq(getMember(ref(ValueReferences.ITERATOR_REF), "value"), value("c")));
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(Map, {@it.value == \"c\"})", print(expression));
  }

  @Test
  void testLargeMapHasAny() {
    Map<Integer, Integer> largeMap = new HashMap<>();
    for (int i = 0; i < 1_000_000; i++) {
      largeMap.put(i, i);
    }
    ValueExpression<?> targetExpression = value(largeMap);

    HasAnyExpression expression = any(targetExpression, FALSE);
    EvalContext timeoutEvalContext = createEvalContext(this, Duration.ofMillis(1));
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression.evaluate(timeoutEvalContext));
    assertEquals("timeout (1ms)", evaluationException.getMessage());
    assertEquals("any(Map, {false})", print(expression));
  }

  @Test
  void testSetHasAny() {
    Set<String> valueSet = new HashSet<>();
    valueSet.add("foo");
    valueSet.add("bar");

    ValueExpression<?> targetExpression = value(valueSet);
    HasAnyExpression expression = any(targetExpression, TRUE);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Set, {true})", print(expression));

    targetExpression = value(valueSet);
    expression = any(targetExpression, FALSE);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(Set, {false})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Set, {@it == \"foo\"})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.ITERATOR_REF), value("key")));
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(Set, {@it == \"key\"})", print(expression));
  }

  @Test
  void testLargeSetHasAny() {
    Set<Integer> largeSet = new HashSet<>();
    for (int i = 0; i < 1_000_000; i++) {
      largeSet.add(i);
    }
    ValueExpression<?> targetExpression = value(largeSet);

    HasAnyExpression expression = any(targetExpression, FALSE);
    EvalContext timeoutEvalContext = createEvalContext(this, Duration.ofMillis(1));
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression.evaluate(timeoutEvalContext));
    assertEquals("timeout (1ms)", evaluationException.getMessage());
    assertEquals("any(Set, {false})", print(expression));
  }

  @Test
  void emptiness() {
    HasAnyExpression expression = any(value(Collections.emptyList()), TRUE);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(List, {true})", print(expression));

    expression = any(value(Collections.emptyMap()), TRUE);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(Map, {true})", print(expression));

    expression = any(value(Collections.emptySet()), TRUE);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("any(Set, {true})", print(expression));
  }

  @Test
  void keyValueMap() {
    Map<String, String> valueMap = new HashMap<>();
    valueMap.put("a", "a");
    valueMap.put("b", null);

    ValueExpression<?> targetExpression = value(valueMap);
    HasAnyExpression expression =
        any(targetExpression, eq(ref(ValueReferences.KEY_REF), value("b")));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Map, {@key == \"b\"})", print(expression));

    expression = any(targetExpression, eq(ref(ValueReferences.VALUE_REF), value("a")));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("any(Map, {@value == \"a\"})", print(expression));
  }

  @Test
  void testUnsupportedList() {
    ListValue collection = new ListValue(new CustomList());
    HasAnyExpression expression =
        any(collection, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals(
        "Unsupported List class: com.datadog.debugger.el.expressions.HasAnyExpressionTest$CustomList",
        exception.getMessage());
    assertEquals("any(List, {@it == \"foo\"})", print(expression));
  }

  @Test
  void testUnsupportedMap() {
    MapValue collection = new MapValue(new CustomMap());
    HasAnyExpression expression = any(collection, eq(ref(ValueReferences.VALUE_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals(
        "Unsupported Map class: com.datadog.debugger.el.expressions.HasAnyExpressionTest$CustomMap",
        exception.getMessage());
    assertEquals("any(Map, {@value == \"foo\"})", print(expression));
  }

  @Test
  void testUnsupportedSet() {
    SetValue collection = new SetValue(new CustomSet());
    HasAnyExpression expression =
        any(collection, eq(ref(ValueReferences.ITERATOR_REF), value("foo")));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals(
        "Unsupported Set class: com.datadog.debugger.el.expressions.HasAnyExpressionTest$CustomSet",
        exception.getMessage());
    assertEquals("any(Set, {@it == \"foo\"})", print(expression));
  }

  static class CustomList extends java.util.ArrayList<String> {}

  static class CustomMap extends HashMap<String, Integer> {}

  static class CustomSet extends java.util.HashSet<String> {}
}
