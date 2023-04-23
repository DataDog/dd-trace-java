package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static com.datadog.debugger.el.expressions.ComparisonOperator.EQ;
import static com.datadog.debugger.el.expressions.ComparisonOperator.GE;
import static com.datadog.debugger.el.expressions.ComparisonOperator.GT;
import static com.datadog.debugger.el.expressions.ComparisonOperator.LE;
import static com.datadog.debugger.el.expressions.ComparisonOperator.LT;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ComparisonExpressionTest {

  @ParameterizedTest
  @MethodSource("expressions")
  void evaluateOperator(
      ValueExpression<?> left,
      ValueExpression<?> right,
      ComparisonOperator operator,
      boolean expected,
      String prettyPrint) {
    ComparisonExpression expression = new ComparisonExpression(left, right, operator);
    assertEquals(expected, expression.evaluate(NoopResolver.INSTANCE));
    assertEquals(prettyPrint, print(expression));
  }

  private static Stream<Arguments> expressions() {
    return Stream.of(
        Arguments.of(new NumericValue(1), new NumericValue(1), EQ, true, "1 == 1"),
        Arguments.of(new NumericValue(1L), new NumericValue(1L), EQ, true, "1 == 1"),
        Arguments.of(new NumericValue(1.0F), new NumericValue(1.0F), EQ, true, "1.0 == 1.0"),
        Arguments.of(new NumericValue(1.0), new NumericValue(1.0), EQ, true, "1.0 == 1.0"),
        Arguments.of(new NumericValue(1), new NumericValue(1.0), EQ, true, "1 == 1.0"),
        Arguments.of(new NumericValue(1), new NumericValue(2), EQ, false, "1 == 2"),
        Arguments.of(new NumericValue(1), new NumericValue(2.0), EQ, false, "1 == 2.0"),
        Arguments.of(new StringValue("foo"), new NumericValue(2), EQ, false, "\"foo\" == 2"),
        Arguments.of(new NumericValue(1), new StringValue("foo"), EQ, false, "1 == \"foo\""),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), EQ, false, "null == 2"),
        Arguments.of(
            new NumericValue(Double.NaN), new NumericValue(Double.NaN), EQ, false, "NaN == NaN"),
        Arguments.of(new NumericValue(1), new NumericValue(1), GT, false, "1 > 1"),
        Arguments.of(new NumericValue(1), new NumericValue(2), GT, false, "1 > 2"),
        Arguments.of(new NumericValue(1.0), new NumericValue(1.1), GT, false, "1.0 > 1.1"),
        Arguments.of(new NumericValue(2), new NumericValue(1), GT, true, "2 > 1"),
        Arguments.of(new NumericValue(1.1), new NumericValue(1.0), GT, true, "1.1 > 1.0"),
        Arguments.of(new NumericValue(1.1), new NumericValue(1), GT, true, "1.1 > 1"),
        Arguments.of(new NumericValue(1), new NumericValue(0.9), GT, true, "1 > 0.9"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), GT, false, "null > 2"),
        Arguments.of(new NumericValue(2), ValueExpression.NULL, GT, false, "2 > null"),
        Arguments.of(
            new NumericValue(Double.NaN), new NumericValue(Double.NaN), GT, false, "NaN > NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2)),
            new NumericValue(BigDecimal.valueOf(1)),
            GT,
            true,
            "2 > 1"),
        Arguments.of(new NumericValue(1), new NumericValue(2), GE, false, "1 >= 2"),
        Arguments.of(new NumericValue(1.0), new NumericValue(1.1), GE, false, "1.0 >= 1.1"),
        Arguments.of(new NumericValue(2), new NumericValue(1), GE, true, "2 >= 1"),
        Arguments.of(new NumericValue(1.1), new NumericValue(1.0), GE, true, "1.1 >= 1.0"),
        Arguments.of(new NumericValue(1.1), new NumericValue(1), GE, true, "1.1 >= 1"),
        Arguments.of(new NumericValue(1), new NumericValue(0.9), GE, true, "1 >= 0.9"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), GE, false, "null >= 2"),
        Arguments.of(
            new NumericValue(Double.NaN), new NumericValue(Double.NaN), GE, false, "NaN >= NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2)),
            new NumericValue(BigDecimal.valueOf(1)),
            GE,
            true,
            "2 >= 1"),
        Arguments.of(new NumericValue(1), new NumericValue(1), LT, false, "1 < 1"),
        Arguments.of(new NumericValue(1), new NumericValue(2), LT, true, "1 < 2"),
        Arguments.of(new NumericValue(2), new NumericValue(1), LT, false, "2 < 1"),
        Arguments.of(new NumericValue(1.1), new NumericValue(1.0), LT, false, "1.1 < 1.0"),
        Arguments.of(new NumericValue(1.0), new NumericValue(1.1), LT, true, "1.0 < 1.1"),
        Arguments.of(new NumericValue(1), new NumericValue(1.1), LT, true, "1 < 1.1"),
        Arguments.of(new NumericValue(0.9), new NumericValue(1), LT, true, "0.9 < 1"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), LT, false, "null < 2"),
        Arguments.of(
            new NumericValue(Double.NaN), new NumericValue(Double.NaN), LT, false, "NaN < NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1)),
            new NumericValue(BigDecimal.valueOf(2)),
            LT,
            true,
            "1 < 2"),
        Arguments.of(new NumericValue(1), new NumericValue(1), LE, true, "1 <= 1"),
        Arguments.of(new NumericValue(1), new NumericValue(2), LE, true, "1 <= 2"),
        Arguments.of(new NumericValue(2), new NumericValue(1), LE, false, "2 <= 1"),
        Arguments.of(new NumericValue(1.1), new NumericValue(1.0), LE, false, "1.1 <= 1.0"),
        Arguments.of(new NumericValue(1.0), new NumericValue(1.1), LE, true, "1.0 <= 1.1"),
        Arguments.of(new NumericValue(1), new NumericValue(1.1), LE, true, "1 <= 1.1"),
        Arguments.of(new NumericValue(0.9), new NumericValue(1), LE, true, "0.9 <= 1"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), LE, false, "null <= 2"),
        Arguments.of(
            new NumericValue(Double.NaN), new NumericValue(Double.NaN), LE, false, "NaN <= NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1)),
            new NumericValue(BigDecimal.valueOf(2)),
            LE,
            true,
            "1 <= 2"));
  }

  @ParameterizedTest
  @MethodSource("expressionStrs")
  void evaluateOperatorStrings(
      ValueExpression<?> left,
      ValueExpression<?> right,
      ComparisonOperator operator,
      boolean expected,
      String prettyPrint) {
    ComparisonExpression expression = new ComparisonExpression(left, right, operator);
    assertEquals(expected, expression.evaluate(NoopResolver.INSTANCE));
    assertEquals(prettyPrint, print(expression));
  }

  private static Stream<Arguments> expressionStrs() {
    return Stream.of(
        Arguments.of(
            new StringValue("foo"), new StringValue("foo"), EQ, true, "\"foo\" == \"foo\""),
        Arguments.of(
            new StringValue("foo"), new StringValue("bar"), EQ, false, "\"foo\" == \"bar\""),
        Arguments.of(new StringValue("foo"), new StringValue("bar"), GT, true, "\"foo\" > \"bar\""),
        Arguments.of(
            new StringValue("bar"), new StringValue("foo"), GT, false, "\"bar\" > \"foo\""),
        Arguments.of(
            new StringValue("foo"), new StringValue("bar"), GE, true, "\"foo\" >= \"bar\""),
        Arguments.of(
            new StringValue("bar"), new StringValue("foo"), GE, false, "\"bar\" >= \"foo\""),
        Arguments.of(new StringValue("bar"), new StringValue("foo"), LT, true, "\"bar\" < \"foo\""),
        Arguments.of(
            new StringValue("foo"), new StringValue("bar"), LT, false, "\"foo\" < \"bar\""),
        Arguments.of(
            new StringValue("bar"), new StringValue("foo"), LE, true, "\"bar\" <= \"foo\""),
        Arguments.of(
            new StringValue("foo"), new StringValue("bar"), LE, false, "\"foo\" <= \"bar\""));
  }

  @Test
  void evaluateSecondUndefined() {
    ComparisonExpression expression =
        new ComparisonExpression(new NumericValue(1), ValueExpression.UNDEFINED, EQ);
    assertFalse(expression.evaluate(NoopResolver.INSTANCE));
  }

  @Test
  void evaluateBothUndefined() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.UNDEFINED, ValueExpression.UNDEFINED, EQ);
    assertFalse(expression.evaluate(NoopResolver.INSTANCE));
  }

  @Test
  void evaluateFirstNull() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.NULL, new NumericValue(2), EQ);
    assertFalse(expression.evaluate(NoopResolver.INSTANCE));
  }

  @Test
  void evaluateSecondNull() {
    ComparisonExpression expression =
        new ComparisonExpression(new NumericValue(1), ValueExpression.NULL, EQ);
    assertFalse(expression.evaluate(NoopResolver.INSTANCE));
  }

  @Test
  void evaluateBothNull() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.NULL, ValueExpression.NULL, EQ);
    assertTrue(expression.evaluate(NoopResolver.INSTANCE));
  }

  private static class NoopResolver implements ValueReferenceResolver {
    static ValueReferenceResolver INSTANCE = new NoopResolver();

    @Override
    public Object lookup(String name) {
      return Value.undefinedValue();
    }

    @Override
    public Object getMember(Object target, String name) {
      return Value.undefinedValue();
    }
  }
}
