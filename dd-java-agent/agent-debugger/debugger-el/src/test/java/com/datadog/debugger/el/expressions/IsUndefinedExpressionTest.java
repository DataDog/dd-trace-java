package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class IsUndefinedExpressionTest {
  private final ValueReferenceResolver resolver = StaticValueRefResolver.self(this);

  @Test
  void testNullValue() {
    assertFalse(new IsUndefinedExpression(null).evaluate(resolver).test());
    assertFalse(new IsUndefinedExpression(DSL.value(Values.NULL_OBJECT)).evaluate(resolver).test());
    assertFalse(new IsUndefinedExpression(DSL.value(Value.nullValue())).evaluate(resolver).test());
  }

  @Test
  void testUndefinedValue() {
    assertTrue(
        new IsUndefinedExpression(DSL.value(Values.UNDEFINED_OBJECT)).evaluate(resolver).test());
  }

  @Test
  void testNumericLiteral() {
    NumericValue zero = new NumericValue(0);
    NumericValue one = new NumericValue(1);
    NumericValue none = new NumericValue(null);

    assertFalse(new IsUndefinedExpression(zero).evaluate(resolver).test());
    assertFalse(new IsUndefinedExpression(one).evaluate(resolver).test());
    assertFalse(new IsUndefinedExpression(none).evaluate(resolver).test());
  }

  @Test
  void testBooleanLiteral() {
    BooleanValue yes = BooleanValue.TRUE;
    BooleanValue no = BooleanValue.FALSE;
    BooleanValue none = new BooleanValue(null);

    assertFalse(new IsUndefinedExpression(yes).evaluate(resolver).test());
    assertFalse(new IsUndefinedExpression(no).evaluate(resolver).test());
    assertFalse(new IsUndefinedExpression(none).evaluate(resolver).test());
  }

  @Test
  void testStringLiteral() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");
    StringValue nullString = new StringValue(null);

    IsUndefinedExpression isUndefined1 = new IsUndefinedExpression(string);
    IsUndefinedExpression isUndefined2 = new IsUndefinedExpression(emptyString);
    IsUndefinedExpression isUndefined3 = new IsUndefinedExpression(nullString);

    assertFalse(isUndefined1.evaluate(resolver).test());
    assertFalse(isUndefined2.evaluate(resolver).test());
    assertFalse(isUndefined3.evaluate(resolver).test());
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

    assertFalse(isUndefined1.evaluate(resolver).test());
    assertFalse(isUndefined2.evaluate(resolver).test());
    assertFalse(isUndefined3.evaluate(resolver).test());
    assertTrue(isUndefined4.evaluate(resolver).test());
  }

  @Test
  void testMapValue() {
    MapValue map = new MapValue(Collections.singletonMap("a", "b"));
    MapValue emptyMap = new MapValue(Collections.emptyMap());
    MapValue nullMao = new MapValue(null);
    MapValue undefinedMap = new MapValue(Values.UNDEFINED_OBJECT);

    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);
    IsUndefinedExpression isUndefined1 = new IsUndefinedExpression(map);
    IsUndefinedExpression isUndefined2 = new IsUndefinedExpression(emptyMap);
    IsUndefinedExpression isUndefined3 = new IsUndefinedExpression(nullMao);
    IsUndefinedExpression isUndefined4 = new IsUndefinedExpression(undefinedMap);

    assertFalse(isUndefined1.evaluate(resolver).test());
    assertFalse(isUndefined2.evaluate(resolver).test());
    assertFalse(isUndefined3.evaluate(resolver).test());
    assertTrue(isUndefined4.evaluate(resolver).test());
  }
}
