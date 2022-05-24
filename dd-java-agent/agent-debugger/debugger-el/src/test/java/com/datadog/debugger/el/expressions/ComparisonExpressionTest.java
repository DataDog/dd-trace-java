package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.predicates.EqualsPredicate;
import com.datadog.debugger.el.predicates.ValuePredicate;
import com.datadog.debugger.el.values.NumericValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.internal.verification.VerificationModeFactory;

class ComparisonExpressionTest {
  private ValuePredicate.Combiner combiner;

  @BeforeEach
  void setup() throws Exception {
    combiner = Mockito.mock(ValuePredicate.Combiner.class);
    Mockito.when(combiner.get(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(new EqualsPredicate(Value.of(1), Value.of(2)));
  }

  @Test
  void evaluate() {
    ComparisonExpression expression =
        new ComparisonExpression(new NumericValue(1), new NumericValue(2), combiner);
    assertFalse(expression.evaluate(path -> Value.undefinedValue()).test());
    Mockito.verify(combiner, VerificationModeFactory.times(1))
        .get(ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void evaluateFirstUndefined() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.UNDEFINED, new NumericValue(2), combiner);
    assertFalse(expression.evaluate(path -> Value.undefinedValue()).test());
    Mockito.verify(combiner, VerificationModeFactory.noInteractions())
        .get(ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void evaluateSecondUndefined() {
    ComparisonExpression expression =
        new ComparisonExpression(new NumericValue(1), ValueExpression.UNDEFINED, combiner);
    assertFalse(expression.evaluate(path -> Value.undefinedValue()).test());
    Mockito.verify(combiner, VerificationModeFactory.noInteractions())
        .get(ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void evaluateBothUndefined() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.UNDEFINED, ValueExpression.UNDEFINED, combiner);
    assertFalse(expression.evaluate(path -> Value.undefinedValue()).test());
    Mockito.verify(combiner, VerificationModeFactory.noInteractions())
        .get(ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void evaluateFirstNull() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.NULL, new NumericValue(2), combiner);
    assertFalse(expression.evaluate(path -> Value.undefinedValue()).test());
    Mockito.verify(combiner, VerificationModeFactory.only())
        .get(ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void evaluateSecondNull() {
    ComparisonExpression expression =
        new ComparisonExpression(new NumericValue(1), ValueExpression.NULL, combiner);
    assertFalse(expression.evaluate(path -> Value.undefinedValue()).test());
    Mockito.verify(combiner, VerificationModeFactory.only())
        .get(ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void evaluateBothNull() {
    ComparisonExpression expression =
        new ComparisonExpression(ValueExpression.NULL, ValueExpression.NULL, combiner);
    assertFalse(expression.evaluate(path -> Value.undefinedValue()).test());
    Mockito.verify(combiner, VerificationModeFactory.only())
        .get(ArgumentMatchers.any(), ArgumentMatchers.any());
  }
}
