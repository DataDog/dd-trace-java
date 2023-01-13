package com.datadog.debugger.el.expressions;

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
      boolean expected) {
    ComparisonExpression expression = new ComparisonExpression(left, right, operator);
    assertEquals(expected, expression.evaluate(NoopResolver.INSTANCE));
  }

  private static Stream<Arguments> expressions() {
    return Stream.of(
        Arguments.of(new NumericValue(1), new NumericValue(1), EQ, true),
        Arguments.of(new NumericValue(1), new NumericValue(2), EQ, false),
        Arguments.of(new StringValue("foo"), new NumericValue(2), EQ, false),
        Arguments.of(new StringValue("foo"), new StringValue("foo"), EQ, true),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), EQ, false),
        Arguments.of(new NumericValue(1), new NumericValue(1), GT, false),
        Arguments.of(new NumericValue(1), new NumericValue(2), GT, false),
        Arguments.of(new NumericValue(2), new NumericValue(1), GT, true),
        Arguments.of(new NumericValue(1.1), new NumericValue(1.0), GT, true),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), GT, false),
        Arguments.of(new NumericValue(2), ValueExpression.NULL, GT, false),
        Arguments.of(new NumericValue(Double.NaN), new NumericValue(Double.NaN), GT, false),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2)),
            new NumericValue(BigDecimal.valueOf(1)),
            GT,
            true),
        Arguments.of(new NumericValue(1), new NumericValue(2), GE, false),
        Arguments.of(new NumericValue(2), new NumericValue(1), GE, true),
        Arguments.of(new NumericValue(1.1), new NumericValue(1.0), GE, true),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), GE, false),
        Arguments.of(new NumericValue(Double.NaN), new NumericValue(Double.NaN), GE, true),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2)),
            new NumericValue(BigDecimal.valueOf(1)),
            GE,
            true),
        Arguments.of(new NumericValue(1), new NumericValue(1), LT, false),
        Arguments.of(new NumericValue(1), new NumericValue(2), LT, true),
        Arguments.of(new NumericValue(2), new NumericValue(1), LT, false),
        Arguments.of(new NumericValue(1.0), new NumericValue(1.1), LT, true),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), LT, false),
        Arguments.of(new NumericValue(Double.NaN), new NumericValue(Double.NaN), LT, false),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1)),
            new NumericValue(BigDecimal.valueOf(2)),
            LT,
            true),
        Arguments.of(new NumericValue(1), new NumericValue(1), LE, true),
        Arguments.of(new NumericValue(1), new NumericValue(2), LE, true),
        Arguments.of(new NumericValue(2), new NumericValue(1), LE, false),
        Arguments.of(new NumericValue(1.0), new NumericValue(1.1), LE, true),
        Arguments.of(ValueExpression.NULL, new NumericValue(2), LE, false),
        Arguments.of(new NumericValue(Double.NaN), new NumericValue(Double.NaN), LE, true),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1)),
            new NumericValue(BigDecimal.valueOf(2)),
            LE,
            true));
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
