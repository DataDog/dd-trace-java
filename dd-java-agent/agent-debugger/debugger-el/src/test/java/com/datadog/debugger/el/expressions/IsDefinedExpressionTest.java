package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class IsDefinedExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);

  @Test
  void testNullValue() {
    IsDefinedExpression expression = new IsDefinedExpression(null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isDefined(null)", print(expression));
    expression = new IsDefinedExpression(DSL.value(Values.NULL_OBJECT));
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(null)", print(expression));
    expression = new IsDefinedExpression(DSL.value(Value.nullValue()));
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(com.datadog.debugger.el.values.NullValue)", print(expression));
  }

  @Test
  void testUndefinedValue() {
    IsDefinedExpression expression = new IsDefinedExpression(DSL.value(Values.UNDEFINED_OBJECT));
    assertFalse(expression.evaluate(resolver));
    assertEquals("isDefined(UNDEFINED)", print(expression));
    expression = new IsDefinedExpression(DSL.ref("undefinedvar"));
    assertFalse(expression.evaluate(resolver));
  }

  @Test
  void testNumericLiteral() {
    NumericValue zero = new NumericValue(0, ValueType.INT);
    NumericValue one = new NumericValue(1, ValueType.INT);
    NumericValue none = new NumericValue(null, ValueType.OBJECT);

    IsDefinedExpression expression = new IsDefinedExpression(zero);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(0)", print(expression));
    expression = new IsDefinedExpression(one);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(1)", print(expression));
    expression = new IsDefinedExpression(none);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(null)", print(expression));
  }

  @Test
  void testBooleanLiteral() {
    BooleanValue yes = BooleanValue.TRUE;
    BooleanValue no = BooleanValue.FALSE;
    BooleanValue none = new BooleanValue(null, ValueType.OBJECT);

    IsDefinedExpression expression = new IsDefinedExpression(yes);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(true)", print(expression));
    expression = new IsDefinedExpression(no);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(false)", print(expression));
    expression = new IsDefinedExpression(none);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isDefined(null)", print(expression));
  }

  @Test
  void testStringLiteral() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");
    StringValue nullString = new StringValue(null);

    IsDefinedExpression isDefined1 = new IsDefinedExpression(string);
    IsDefinedExpression isDefined2 = new IsDefinedExpression(emptyString);
    IsDefinedExpression isDefined3 = new IsDefinedExpression(nullString);

    assertTrue(isDefined1.evaluate(resolver));
    assertEquals("isDefined(\"Hello World\")", print(isDefined1));
    assertTrue(isDefined2.evaluate(resolver));
    assertEquals("isDefined(\"\")", print(isDefined2));
    assertTrue(isDefined3.evaluate(resolver));
    assertEquals("isDefined(\"null\")", print(isDefined3));
  }

  @Test
  void testListValue() {
    ListValue list = new ListValue(Arrays.asList("a", "b"));
    ListValue emptyList = new ListValue(Collections.emptyList());
    ListValue nullList = new ListValue(null);
    ListValue undefinedList = new ListValue(Values.UNDEFINED_OBJECT);

    IsDefinedExpression isDefined1 = new IsDefinedExpression(list);
    IsDefinedExpression isDefined2 = new IsDefinedExpression(emptyList);
    IsDefinedExpression isDefined3 = new IsDefinedExpression(nullList);
    IsDefinedExpression isDefined4 = new IsDefinedExpression(undefinedList);

    assertTrue(isDefined1.evaluate(resolver));
    assertEquals("isDefined(List)", print(isDefined1));
    assertTrue(isDefined2.evaluate(resolver));
    assertEquals("isDefined(List)", print(isDefined2));
    assertTrue(isDefined3.evaluate(resolver));
    assertEquals("isDefined(null)", print(isDefined3));
    assertFalse(isDefined4.evaluate(resolver));
    assertEquals("isDefined(null)", print(isDefined4));
  }

  @Test
  void testMapValue() {
    MapValue map = new MapValue(Collections.singletonMap("a", "b"));
    MapValue emptyMap = new MapValue(Collections.emptyMap());
    MapValue nullMao = new MapValue(null);
    MapValue undefinedMap = new MapValue(Values.UNDEFINED_OBJECT);

    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsDefinedExpression isDefined1 = new IsDefinedExpression(map);
    IsDefinedExpression isDefined2 = new IsDefinedExpression(emptyMap);
    IsDefinedExpression isDefined3 = new IsDefinedExpression(nullMao);
    IsDefinedExpression isDefined4 = new IsDefinedExpression(undefinedMap);

    assertTrue(isDefined1.evaluate(resolver));
    assertEquals("isDefined(Map)", print(isDefined1));
    assertTrue(isDefined2.evaluate(resolver));
    assertEquals("isDefined(Map)", print(isDefined2));
    assertTrue(isDefined3.evaluate(resolver));
    assertEquals("isDefined(null)", print(isDefined3));
    assertFalse(isDefined4.evaluate(resolver));
    assertEquals("isDefined(null)", print(isDefined4));
  }
}
