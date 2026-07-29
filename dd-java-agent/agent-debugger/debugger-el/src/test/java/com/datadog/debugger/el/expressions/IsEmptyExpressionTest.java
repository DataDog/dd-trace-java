package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.EvalContextHelper.createEvalContext;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class IsEmptyExpressionTest {
  EvalContext evalContext = createEvalContext(this);

  @Test
  void testNullValue() {
    IsEmptyExpression expression1 = new IsEmptyExpression(null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression1.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("isEmpty(null)", print(expression1));
    IsEmptyExpression expression2 = new IsEmptyExpression(DSL.value(Values.NULL_OBJECT));
    exception = assertThrows(EvaluationException.class, () -> expression2.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("isEmpty(null)", print(expression2));
    IsEmptyExpression expression3 = new IsEmptyExpression(DSL.value(Value.nullValue()));
    exception = assertThrows(EvaluationException.class, () -> expression3.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("isEmpty(com.datadog.debugger.el.values.NullValue)", print(expression3));
  }

  @Test
  void testUndefinedValue() {
    IsEmptyExpression expression = new IsEmptyExpression(DSL.value(Values.UNDEFINED_OBJECT));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("isEmpty(UNDEFINED)", print(expression));
  }

  @Test
  void testNumericLiteral() {
    NumericValue zero = new NumericValue(0, ValueType.INT);
    NumericValue one = new NumericValue(1, ValueType.INT);
    NumericValue none = new NumericValue(null, ValueType.OBJECT);

    IsEmptyExpression expression = new IsEmptyExpression(zero);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("isEmpty(0)", print(expression));
    expression = new IsEmptyExpression(one);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("isEmpty(1)", print(expression));
    IsEmptyExpression nullExpression = new IsEmptyExpression(none);
    assertThrows(EvaluationException.class, () -> nullExpression.evaluate(evalContext));
    assertEquals("isEmpty(null)", print(nullExpression));
  }

  @Test
  void testBooleanLiteral() {
    BooleanValue yes = BooleanValue.TRUE;
    BooleanValue no = BooleanValue.FALSE;
    BooleanValue none = new BooleanValue(null, ValueType.OBJECT);

    IsEmptyExpression expression = new IsEmptyExpression(yes);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("isEmpty(true)", print(expression));
    expression = new IsEmptyExpression(no);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("isEmpty(false)", print(expression));
    IsEmptyExpression nullExpression = new IsEmptyExpression(none);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> nullExpression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("isEmpty(null)", print(nullExpression));
  }

  @Test
  void testStringLiteral() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");
    StringValue nullString = new StringValue(null);

    IsEmptyExpression isEmpty1 = new IsEmptyExpression(string);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyString);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullString);

    assertFalse(isEmpty1.evaluate(evalContext));
    assertEquals("isEmpty(\"Hello World\")", print(isEmpty1));
    assertTrue(isEmpty2.evaluate(evalContext));
    assertEquals("isEmpty(\"\")", print(isEmpty2));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> isEmpty3.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("isEmpty(\"null\")", print(isEmpty3));
  }

  @Test
  void testCollectionLiteral() {
    ListValue list = new ListValue(Arrays.asList("a", "b"));
    ListValue emptyList = new ListValue(Collections.emptyList());
    ListValue nullList = new ListValue(null);
    ListValue undefinedList = new ListValue(Values.UNDEFINED_OBJECT);
    ListValue set = new ListValue(new HashSet<>(Arrays.asList("a", "b")));
    ListValue emptySet = new ListValue(Collections.emptySet());

    IsEmptyExpression isEmpty1 = new IsEmptyExpression(list);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyList);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullList);
    IsEmptyExpression isEmpty4 = new IsEmptyExpression(undefinedList);
    IsEmptyExpression isEmpty5 = new IsEmptyExpression(set);
    IsEmptyExpression isEmpty6 = new IsEmptyExpression(emptySet);

    assertFalse(isEmpty1.evaluate(evalContext));
    assertEquals("isEmpty(List)", print(isEmpty1));
    assertTrue(isEmpty2.evaluate(evalContext));
    assertEquals("isEmpty(List)", print(isEmpty2));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> isEmpty3.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("isEmpty(null)", print(isEmpty3));
    exception = assertThrows(EvaluationException.class, () -> isEmpty4.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("isEmpty(null)", print(isEmpty4));
    assertFalse(isEmpty5.evaluate(evalContext));
    assertEquals("isEmpty(Set)", print(isEmpty5));
    assertTrue(isEmpty6.evaluate(evalContext));
    assertEquals("isEmpty(Set)", print(isEmpty6));
  }

  @Test
  void testMapValue() {
    MapValue map = new MapValue(Collections.singletonMap("a", "b"));
    MapValue emptyMap = new MapValue(Collections.emptyMap());
    MapValue nullMao = new MapValue(null);
    MapValue undefinedMap = new MapValue(Values.UNDEFINED_OBJECT);

    IsEmptyExpression isEmpty1 = new IsEmptyExpression(map);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyMap);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullMao);
    IsEmptyExpression isEmpty4 = new IsEmptyExpression(undefinedMap);

    assertFalse(isEmpty1.evaluate(evalContext));
    assertEquals("isEmpty(Map)", print(isEmpty1));
    assertTrue(isEmpty2.evaluate(evalContext));
    assertEquals("isEmpty(Map)", print(isEmpty2));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> isEmpty3.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("isEmpty(null)", print(isEmpty3));
    exception = assertThrows(EvaluationException.class, () -> isEmpty4.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("isEmpty(null)", print(isEmpty4));
  }
}
