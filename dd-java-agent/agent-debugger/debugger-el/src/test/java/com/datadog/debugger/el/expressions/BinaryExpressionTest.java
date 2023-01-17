package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.RefResolverHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BinaryExpressionTest {
  @Test
  void testLeftNull() {
    BinaryExpression expression =
        new BinaryExpression(null, BooleanExpression.TRUE, BinaryOperator.AND);
    Assertions.assertFalse(expression.evaluate(RefResolverHelper.createResolver(this)));
  }

  @Test
  void testRightNull() {
    BinaryExpression expression =
        new BinaryExpression(BooleanExpression.TRUE, null, BinaryOperator.AND);
    Assertions.assertFalse(expression.evaluate(RefResolverHelper.createResolver(this)));
  }
}
