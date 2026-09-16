package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.EvalContextHelper.createEvalContext;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.EvalContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BinaryExpressionTest {
  EvalContext evalContext = createEvalContext(this);

  @Test
  void testLeftNull() {
    BinaryExpression expression =
        new BinaryExpression(null, BooleanExpression.TRUE, BinaryOperator.AND);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("false && true", print(expression));
  }

  @Test
  void testRightNull() {
    BinaryExpression expression =
        new BinaryExpression(BooleanExpression.TRUE, null, BinaryOperator.AND);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("true && false", print(expression));
  }

  @Test
  void testShortCircuitAnd() {
    BinaryExpression expression =
        new BinaryExpression(
            BooleanExpression.FALSE,
            valueRefResolver -> Assertions.fail("should not reach"),
            BinaryOperator.AND);
    assertFalse(expression.evaluate(evalContext));
    assertEquals("false && null", print(expression));
  }

  @Test
  void testShortCircuitOr() {
    BinaryExpression expression =
        new BinaryExpression(
            BooleanExpression.TRUE,
            valueRefResolver -> Assertions.fail("should not reach"),
            BinaryOperator.OR);
    assertTrue(expression.evaluate(evalContext));
    assertEquals("true || null", print(expression));
  }
}
