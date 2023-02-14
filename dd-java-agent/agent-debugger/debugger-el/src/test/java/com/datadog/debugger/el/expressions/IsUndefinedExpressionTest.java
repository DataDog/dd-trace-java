package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.Value;
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

class IsUndefinedExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);

  @Test
  void testNullValue() {
    IsUndefinedExpression expression = new IsUndefinedExpression(null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(null)", expression.prettyPrint());
    expression = new IsUndefinedExpression(DSL.value(Values.NULL_OBJECT));
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(null)", expression.prettyPrint());
    expression = new IsUndefinedExpression(DSL.value(Value.nullValue()));
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(com.datadog.debugger.el.values.NullValue)", expression.prettyPrint());
  }

  @Test
  void testUndefinedValue() {
    IsUndefinedExpression expression =
        new IsUndefinedExpression(DSL.value(Values.UNDEFINED_OBJECT));
    assertTrue(expression.evaluate(resolver));
    assertEquals("isUndefined(UNDEFINED)", expression.prettyPrint());
  }

  @Test
  void testNumericLiteral() {
    NumericValue zero = new NumericValue(0);
    NumericValue one = new NumericValue(1);
    NumericValue none = new NumericValue(null);

    IsUndefinedExpression expression = new IsUndefinedExpression(zero);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(0)", expression.prettyPrint());
    expression = new IsUndefinedExpression(one);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(1)", expression.prettyPrint());
    expression = new IsUndefinedExpression(none);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(null)", expression.prettyPrint());
  }

  @Test
  void testBooleanLiteral() {
    BooleanValue yes = BooleanValue.TRUE;
    BooleanValue no = BooleanValue.FALSE;
    BooleanValue none = new BooleanValue(null);

    IsUndefinedExpression expression = new IsUndefinedExpression(yes);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(true)", expression.prettyPrint());
    expression = new IsUndefinedExpression(no);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(false)", expression.prettyPrint());
    expression = new IsUndefinedExpression(none);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isUndefined(null)", expression.prettyPrint());
  }

  @Test
  void testStringLiteral() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");
    StringValue nullString = new StringValue(null);

    IsUndefinedExpression isUndefined1 = new IsUndefinedExpression(string);
    IsUndefinedExpression isUndefined2 = new IsUndefinedExpression(emptyString);
    IsUndefinedExpression isUndefined3 = new IsUndefinedExpression(nullString);

    assertFalse(isUndefined1.evaluate(resolver));
    assertEquals("isUndefined(\"Hello World\")", isUndefined1.prettyPrint());
    assertFalse(isUndefined2.evaluate(resolver));
    assertEquals("isUndefined(\"\")", isUndefined2.prettyPrint());
    assertFalse(isUndefined3.evaluate(resolver));
    assertEquals("isUndefined(\"null\")", isUndefined3.prettyPrint());
  }

  @Test
  void testListValue() {
    ListValue list = new ListValue(Arrays.asList("a", "b"));
    ListValue emptyList = new ListValue(Collections.emptyList());
    ListValue nullList = new ListValue(null);
    ListValue undefinedList = new ListValue(Values.UNDEFINED_OBJECT);

    IsUndefinedExpression isUndefined1 = new IsUndefinedExpression(list);
    IsUndefinedExpression isUndefined2 = new IsUndefinedExpression(emptyList);
    IsUndefinedExpression isUndefined3 = new IsUndefinedExpression(nullList);
    IsUndefinedExpression isUndefined4 = new IsUndefinedExpression(undefinedList);

    assertFalse(isUndefined1.evaluate(resolver));
    assertEquals("isUndefined(List)", isUndefined1.prettyPrint());
    assertFalse(isUndefined2.evaluate(resolver));
    assertEquals("isUndefined(List)", isUndefined2.prettyPrint());
    assertFalse(isUndefined3.evaluate(resolver));
    assertEquals("isUndefined(null)", isUndefined3.prettyPrint());
    assertTrue(isUndefined4.evaluate(resolver));
    assertEquals("isUndefined(null)", isUndefined4.prettyPrint());
  }

  @Test
  void testMapValue() {
    MapValue map = new MapValue(Collections.singletonMap("a", "b"));
    MapValue emptyMap = new MapValue(Collections.emptyMap());
    MapValue nullMao = new MapValue(null);
    MapValue undefinedMap = new MapValue(Values.UNDEFINED_OBJECT);

    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsUndefinedExpression isUndefined1 = new IsUndefinedExpression(map);
    IsUndefinedExpression isUndefined2 = new IsUndefinedExpression(emptyMap);
    IsUndefinedExpression isUndefined3 = new IsUndefinedExpression(nullMao);
    IsUndefinedExpression isUndefined4 = new IsUndefinedExpression(undefinedMap);

    assertFalse(isUndefined1.evaluate(resolver));
    assertEquals("isUndefined(Map)", isUndefined1.prettyPrint());
    assertFalse(isUndefined2.evaluate(resolver));
    assertEquals("isUndefined(Map)", isUndefined2.prettyPrint());
    assertFalse(isUndefined3.evaluate(resolver));
    assertEquals("isUndefined(null)", isUndefined3.prettyPrint());
    assertTrue(isUndefined4.evaluate(resolver));
    assertEquals("isUndefined(null)", isUndefined4.prettyPrint());
  }
}
