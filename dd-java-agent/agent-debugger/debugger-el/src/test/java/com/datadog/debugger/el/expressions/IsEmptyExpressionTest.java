package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.StaticValueRefResolver;
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

class IsEmptyExpressionTest {
  @Test
  void testNullValue() {
    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);

    assertTrue(new IsEmptyExpression(null).evaluate(resolver).test());
    assertTrue(new IsEmptyExpression(DSL.value(Values.NULL_OBJECT)).evaluate(resolver).test());
    assertTrue(new IsEmptyExpression(DSL.value(Value.nullValue())).evaluate(resolver).test());
  }

  @Test
  void testUndefinedValue() {
    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);

    assertTrue(new IsEmptyExpression(DSL.value(Values.UNDEFINED_OBJECT)).evaluate(resolver).test());
  }

  @Test
  void testNumericLiteral() {
    NumericValue zero = new NumericValue(0);
    NumericValue one = new NumericValue(1);
    NumericValue none = new NumericValue(null);

    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);
    assertFalse(new IsEmptyExpression(zero).evaluate(resolver).test());
    assertFalse(new IsEmptyExpression(one).evaluate(resolver).test());
    assertTrue(new IsEmptyExpression(none).evaluate(resolver).test());
  }

  @Test
  void testBooleanLiteral() {
    BooleanValue yes = BooleanValue.TRUE;
    BooleanValue no = BooleanValue.FALSE;
    BooleanValue none = new BooleanValue(null);

    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);
    assertFalse(new IsEmptyExpression(yes).evaluate(resolver).test());
    assertFalse(new IsEmptyExpression(no).evaluate(resolver).test());
    assertTrue(new IsEmptyExpression(none).evaluate(resolver).test());
  }

  @Test
  void testStringLiteral() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");
    StringValue nullString = new StringValue(null);

    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(string);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyString);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullString);

    assertFalse(isEmpty1.evaluate(resolver).test());
    assertTrue(isEmpty2.evaluate(resolver).test());
    assertTrue(isEmpty3.evaluate(resolver).test());
  }

  @Test
  void testCollectionLiteral() {
    ListValue list = new ListValue(Arrays.asList("a", "b"));
    ListValue emptyList = new ListValue(Collections.emptyList());
    ListValue nullList = new ListValue(null);
    ListValue undefinedList = new ListValue(Values.UNDEFINED_OBJECT);

    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(list);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyList);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullList);
    IsEmptyExpression isEmpty4 = new IsEmptyExpression(undefinedList);

    assertFalse(isEmpty1.evaluate(resolver).test());
    assertTrue(isEmpty2.evaluate(resolver).test());
    assertTrue(isEmpty3.evaluate(resolver).test());
    assertTrue(isEmpty4.evaluate(resolver).test());
  }

  @Test
  void testMapValue() {
    MapValue map = new MapValue(Collections.singletonMap("a", "b"));
    MapValue emptyMap = new MapValue(Collections.emptyMap());
    MapValue nullMao = new MapValue(null);
    MapValue undefinedMap = new MapValue(Values.UNDEFINED_OBJECT);

    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(map);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyMap);
    IsEmptyExpression isEmpty3 = new IsEmptyExpression(nullMao);
    IsEmptyExpression isEmpty4 = new IsEmptyExpression(undefinedMap);

    assertFalse(isEmpty1.evaluate(resolver).test());
    assertTrue(isEmpty2.evaluate(resolver).test());
    assertTrue(isEmpty3.evaluate(resolver).test());
    assertTrue(isEmpty4.evaluate(resolver).test());
  }
}
