package com.datadog.debugger.el;

import static com.datadog.debugger.el.DSL.*;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExpressionTest {
  @ParameterizedTest
  @MethodSource("literalExpressions")
  void testLiteralExpressions(Literal<?> literal, Object expectedValue) {
    Value<?> value1 = literal.evaluate(null);
    Value<?> value2 = literal.evaluate(StaticValueRefResolver.self(ExpressionTest.this));

    assertNotNull(value1);
    assertNotNull(value2);
    assertEquals(value1, value2);

    assertEquals(expectedValue, value1.getValue());
    assertEquals(expectedValue, value2.getValue());
  }

  private static Stream<Arguments> literalExpressions() {
    return Stream.of(
        Arguments.of(new BooleanValue(true), true),
        Arguments.of(new NumericValue(15.8d), 15.8d),
        Arguments.of(new StringValue("Hello world"), "Hello world"));
  }

  @Test
  void testPredicateExpression() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");

    ValueReferenceResolver resolver = StaticValueRefResolver.self(this);
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(string);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyString);

    assertFalse(isEmpty1.evaluate(resolver).test());
    assertTrue(isEmpty2.evaluate(resolver).test());

    assertTrue(not(isEmpty1).evaluate(resolver).test());
    assertTrue(or(isEmpty1, isEmpty2).evaluate(resolver).test());
    assertFalse(and(isEmpty1, isEmpty2).evaluate(resolver).test());
  }
}
