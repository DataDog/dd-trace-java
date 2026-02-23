package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static com.datadog.debugger.el.ValueType.DOUBLE;
import static com.datadog.debugger.el.ValueType.FLOAT;
import static com.datadog.debugger.el.ValueType.INT;
import static com.datadog.debugger.el.ValueType.LONG;
import static com.datadog.debugger.el.ValueType.OBJECT;
import static com.datadog.debugger.el.expressions.ComparisonOperator.EQ;
import static com.datadog.debugger.el.expressions.ComparisonOperator.GE;
import static com.datadog.debugger.el.expressions.ComparisonOperator.GT;
import static com.datadog.debugger.el.expressions.ComparisonOperator.INSTANCEOF;
import static com.datadog.debugger.el.expressions.ComparisonOperator.LE;
import static com.datadog.debugger.el.expressions.ComparisonOperator.LT;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.math.BigDecimal;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ComparisonExpressionTest {

  @ParameterizedTest(name = "[{index}] {4}")
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
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), EQ, true, "1 == 1"),
        Arguments.of(new NumericValue(1L, LONG), new NumericValue(1L, LONG), EQ, true, "1 == 1"),
        Arguments.of(
            new NumericValue(1.0F, FLOAT), new NumericValue(1.0F, FLOAT), EQ, true, "1.0 == 1.0"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.0, DOUBLE), EQ, true, "1.0 == 1.0"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1.0, DOUBLE), EQ, true, "1 == 1.0"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), EQ, false, "1 == 2"),
        Arguments.of(
            new NumericValue(1, INT), new NumericValue(2.0, DOUBLE), EQ, false, "1 == 2.0"),
        Arguments.of(new StringValue("foo"), new NumericValue(2, INT), EQ, false, "\"foo\" == 2"),
        Arguments.of(new NumericValue(1, INT), new StringValue("foo"), EQ, false, "1 == \"foo\""),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), EQ, false, "null == 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            EQ,
            false,
            "NaN == NaN"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), GT, false, "1 > 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), GT, false, "1 > 2"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), GT, false, "1.0 > 1.1"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), GT, true, "2 > 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), GT, true, "1.1 > 1.0"),
        Arguments.of(new NumericValue(1.1, DOUBLE), new NumericValue(1, INT), GT, true, "1.1 > 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(0.9, DOUBLE), GT, true, "1 > 0.9"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), GT, false, "null > 2"),
        Arguments.of(new NumericValue(2, INT), ValueExpression.NULL, GT, false, "2 > null"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            GT,
            false,
            "NaN > NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            GT,
            true,
            "2 > 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), GE, false, "1 >= 2"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), GE, false, "1.0 >= 1.1"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), GE, true, "2 >= 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), GE, true, "1.1 >= 1.0"),
        Arguments.of(new NumericValue(1.1, DOUBLE), new NumericValue(1, INT), GE, true, "1.1 >= 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(0.9, DOUBLE), GE, true, "1 >= 0.9"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), GE, false, "null >= 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            GE,
            false,
            "NaN >= NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            GE,
            true,
            "2 >= 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), LT, false, "1 < 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), LT, true, "1 < 2"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), LT, false, "2 < 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), LT, false, "1.1 < 1.0"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), LT, true, "1.0 < 1.1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1.1, DOUBLE), LT, true, "1 < 1.1"),
        Arguments.of(new NumericValue(0.9, DOUBLE), new NumericValue(1, INT), LT, true, "0.9 < 1"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), LT, false, "null < 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            LT,
            false,
            "NaN < NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            LT,
            true,
            "1 < 2"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), LE, true, "1 <= 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), LE, true, "1 <= 2"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), LE, false, "2 <= 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), LE, false, "1.1 <= 1.0"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), LE, true, "1.0 <= 1.1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1.1, DOUBLE), LE, true, "1 <= 1.1"),
        Arguments.of(new NumericValue(0.9, DOUBLE), new NumericValue(1, INT), LE, true, "0.9 <= 1"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), LE, false, "null <= 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            LE,
            false,
            "NaN <= NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            LE,
            true,
            "1 <= 2"),
        Arguments.of(
            new StringValue("foo"),
            new StringValue("java.lang.String"),
            INSTANCEOF,
            true,
            "\"foo\" instanceof \"java.lang.String\""),
        Arguments.of(
            new StringValue("foo"),
            new StringValue("java.lang.Object"),
            INSTANCEOF,
            true,
            "\"foo\" instanceof \"java.lang.Object\""),
        Arguments.of(
            new ObjectValue(new Random()),
            new StringValue("java.util.Random"),
            INSTANCEOF,
            true,
            "java.util.Random instanceof \"java.util.Random\""),
        Arguments.of(
            new ObjectValue(new ArrayList<>()),
            new StringValue("java.util.List"),
            INSTANCEOF,
            true,
            "java.util.ArrayList instanceof \"java.util.List\""),
        Arguments.of(
            new ObjectValue(new ArrayList<>()),
            new StringValue("java.util.Map"),
            INSTANCEOF,
            false,
            "java.util.ArrayList instanceof \"java.util.Map\""),
        Arguments.of(
            ValueExpression.NULL,
            new StringValue("java.lang.String"),
            INSTANCEOF,
            false,
            "null instanceof \"java.lang.String\""),
        Arguments.of(
            new NumericValue(1, INT),
            new StringValue("java.lang.Integer"),
            INSTANCEOF,
            true,
            "1 instanceof \"java.lang.Integer\""),
        Arguments.of(
            new NumericValue(1.0, DOUBLE),
            new StringValue("java.lang.Double"),
            INSTANCEOF,
            true,
            "1.0 instanceof \"java.lang.Double\""),
        Arguments.of(
            new ObjectValue(StandardOpenOption.READ),
            new StringValue("READ"),
            EQ,
            true,
            "java.nio.file.StandardOpenOption == \"READ\""),
        Arguments.of(
            new ObjectValue(StandardOpenOption.READ),
            new StringValue("StandardOpenOption.READ"),
            EQ,
            true,
            "java.nio.file.StandardOpenOption == \"StandardOpenOption.READ\""),
        Arguments.of(
            new ObjectValue(StandardOpenOption.READ),
            new StringValue("java.nio.file.StandardOpenOption.READ"),
            EQ,
            true,
            "java.nio.file.StandardOpenOption == \"java.nio.file.StandardOpenOption.READ\""),
        Arguments.of(
            new ObjectValue(StandardOpenOption.CREATE),
            new StringValue("READ"),
            EQ,
            false,
            "java.nio.file.StandardOpenOption == \"READ\""),
        Arguments.of(
            new StringValue("READ"),
            new ObjectValue(StandardOpenOption.READ),
            EQ,
            true,
            "\"READ\" == java.nio.file.StandardOpenOption"));
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
        new ComparisonExpression(new NumericValue(1, INT), ValueExpression.UNDEFINED, EQ);
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
        new ComparisonExpression(ValueExpression.NULL, new NumericValue(2, INT), EQ);
    assertFalse(expression.evaluate(NoopResolver.INSTANCE));
  }

  @Test
  void evaluateSecondNull() {
    ComparisonExpression expression =
        new ComparisonExpression(new NumericValue(1, INT), ValueExpression.NULL, EQ);
    assertFalse(expression.evaluate(NoopResolver.INSTANCE));
  }

  @Test
  void evaluateBothNull() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.NULL, ValueExpression.NULL, EQ);
    assertTrue(expression.evaluate(NoopResolver.INSTANCE));
  }

  @Test
  void invalidInstanceofOperand() {
    ComparisonExpression expression =
        new ComparisonExpression(new StringValue("foo"), new NumericValue(1, INT), INSTANCEOF);
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression.evaluate(NoopResolver.INSTANCE));
    assertEquals(
        "Right operand of instanceof operator must be a string literal",
        evaluationException.getMessage());
    assertEquals("\"foo\" instanceof 1", evaluationException.getExpr());
  }

  @Test
  void invalidInstanceofClassName() {
    ComparisonExpression expression =
        new ComparisonExpression(new StringValue("foo"), new StringValue("String"), INSTANCEOF);
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> expression.evaluate(NoopResolver.INSTANCE));
    assertEquals("Class not found: String", evaluationException.getMessage());
    assertEquals("\"foo\" instanceof \"String\"", evaluationException.getExpr());
  }

  private static class NoopResolver implements ValueReferenceResolver {
    static ValueReferenceResolver INSTANCE = new NoopResolver();

    @Override
    public CapturedContext.CapturedValue lookup(String name) {
      return CapturedContext.CapturedValue.UNDEFINED;
    }

    @Override
    public CapturedContext.CapturedValue getMember(Object target, String name) {
      return CapturedContext.CapturedValue.UNDEFINED;
    }
  }
}
