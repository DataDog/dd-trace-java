package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

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
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class IsEmptyExpressionTest {
  @Test
  void testNullValue() {
    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsEmptyExpression expression = new IsEmptyExpression(null);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isEmpty(null)", print(expression));
    expression = new IsEmptyExpression(DSL.value(Values.NULL_OBJECT));
    assertTrue(expression.evaluate(resolver));
    assertEquals("isEmpty(null)", print(expression));
    expression = new IsEmptyExpression(DSL.value(Value.nullValue()));
    assertTrue(expression.evaluate(resolver));
    assertEquals("isEmpty(com.datadog.debugger.el.values.NullValue)", print(expression));
  }

  @Test
  void testUndefinedValue() {
    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsEmptyExpression expression = new IsEmptyExpression(DSL.value(Values.UNDEFINED_OBJECT));
    assertTrue(expression.evaluate(resolver));
    assertEquals("isEmpty(UNDEFINED)", print(expression));
  }

  @Test
  void testNumericLiteral() {
    NumericValue zero = new NumericValue(0);
    NumericValue one = new NumericValue(1);
    NumericValue none = new NumericValue(null);

    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsEmptyExpression expression = new IsEmptyExpression(zero);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isEmpty(0)", print(expression));
    expression = new IsEmptyExpression(one);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isEmpty(1)", print(expression));
    expression = new IsEmptyExpression(none);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isEmpty(null)", print(expression));
  }

  @Test
  void testBooleanLiteral() {
    BooleanValue yes = BooleanValue.TRUE;
    BooleanValue no = BooleanValue.FALSE;
    BooleanValue none = new BooleanValue(null);

    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsEmptyExpression expression = new IsEmptyExpression(yes);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isEmpty(true)", print(expression));
    expression = new IsEmptyExpression(no);
    assertFalse(expression.evaluate(resolver));
    assertEquals("isEmpty(false)", print(expression));
    expression = new IsEmptyExpression(none);
    assertTrue(expression.evaluate(resolver));
    assertEquals("isEmpty(null)", print(expression));
  }

  @Test
  void testStringLiteral() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");
    StringValue nullString = new StringValue(null);

    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(string);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyString);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullString);

    assertFalse(isEmpty1.evaluate(resolver));
    assertEquals("isEmpty(\"Hello World\")", print(isEmpty1));
    assertTrue(isEmpty2.evaluate(resolver));
    assertEquals("isEmpty(\"\")", print(isEmpty2));
    assertTrue(isEmpty3.evaluate(resolver));
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

    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(list);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyList);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullList);
    IsEmptyExpression isEmpty4 = new IsEmptyExpression(undefinedList);
    IsEmptyExpression isEmpty5 = new IsEmptyExpression(set);
    IsEmptyExpression isEmpty6 = new IsEmptyExpression(emptySet);

    assertFalse(isEmpty1.evaluate(resolver));
    assertEquals("isEmpty(List)", print(isEmpty1));
    assertTrue(isEmpty2.evaluate(resolver));
    assertEquals("isEmpty(List)", print(isEmpty2));
    assertTrue(isEmpty3.evaluate(resolver));
    assertEquals("isEmpty(null)", print(isEmpty3));
    assertTrue(isEmpty4.evaluate(resolver));
    assertEquals("isEmpty(null)", print(isEmpty4));
    assertFalse(isEmpty5.evaluate(resolver));
    assertEquals("isEmpty(Set)", print(isEmpty5));
    assertTrue(isEmpty6.evaluate(resolver));
    assertEquals("isEmpty(Set)", print(isEmpty6));
  }

  @Test
  void testMapValue() {
    MapValue map = new MapValue(Collections.singletonMap("a", "b"));
    MapValue emptyMap = new MapValue(Collections.emptyMap());
    MapValue nullMao = new MapValue(null);
    MapValue undefinedMap = new MapValue(Values.UNDEFINED_OBJECT);

    ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(map);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyMap);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullMao);
    IsEmptyExpression isEmpty4 = new IsEmptyExpression(undefinedMap);

    assertFalse(isEmpty1.evaluate(resolver));
    assertEquals("isEmpty(Map)", print(isEmpty1));
    assertTrue(isEmpty2.evaluate(resolver));
    assertEquals("isEmpty(Map)", print(isEmpty2));
    assertTrue(isEmpty3.evaluate(resolver));
    assertEquals("isEmpty(null)", print(isEmpty3));
    assertTrue(isEmpty4.evaluate(resolver));
    assertEquals("isEmpty(null)", print(isEmpty4));
  }
}
