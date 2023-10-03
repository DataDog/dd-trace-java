package com.datadog.debugger.el;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.values.BooleanValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import org.junit.jupiter.api.Test;

class BooleanValueExpressionAdapterTest {

  @Test
  public void testLiteral() {
    {
      BooleanValueExpressionAdapter booleanValueExpressionAdapter =
          new BooleanValueExpressionAdapter(BooleanExpression.TRUE);
      BooleanValue resultValue = booleanValueExpressionAdapter.evaluate(null);
      assertTrue(resultValue.getValue());
    }
    {
      BooleanValueExpressionAdapter booleanValueExpressionAdapter =
          new BooleanValueExpressionAdapter(BooleanExpression.FALSE);
      BooleanValue resultValue = booleanValueExpressionAdapter.evaluate(null);
      assertFalse(resultValue.getValue());
    }
  }

  @Test
  public void testExpression() {
    BooleanValueExpressionAdapter booleanValueExpressionAdapter =
        new BooleanValueExpressionAdapter(DSL.eq(DSL.value(1), DSL.value(1)));
    BooleanValue resultValue = booleanValueExpressionAdapter.evaluate(null);
    assertTrue(resultValue.getValue());
  }

  @Test
  public void testNull() {
    BooleanValueExpressionAdapter booleanValueExpressionAdapter =
        new BooleanValueExpressionAdapter(
            new BooleanExpression() {
              @Override
              public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
                return null;
              }
            });
    EvaluationException ex =
        assertThrows(EvaluationException.class, () -> booleanValueExpressionAdapter.evaluate(null));
    assertEquals("Boolean expression returning null", ex.getMessage());
  }
}
