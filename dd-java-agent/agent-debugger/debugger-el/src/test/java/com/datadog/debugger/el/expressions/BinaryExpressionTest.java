package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadog.debugger.el.RefResolverHelper;
import org.junit.jupiter.api.Test;

class BinaryExpressionTest {
  @Test
  void testLeftNull() {
    BinaryExpression expression =
        new BinaryExpression(null, BooleanExpression.TRUE, BinaryOperator.AND);
    assertFalse(expression.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals("false && true", print(expression));
  }

  @Test
  void testRightNull() {
    BinaryExpression expression =
        new BinaryExpression(BooleanExpression.TRUE, null, BinaryOperator.AND);
    assertFalse(expression.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals("true && false", print(expression));
  }
}
