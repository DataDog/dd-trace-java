package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.StaticValueRefResolver;
import com.datadog.debugger.el.predicates.BinaryPredicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.internal.verification.VerificationModeFactory;

class BinaryExpressionTest {
  @Test
  void testLeftNull() {
    BinaryPredicate mock = Mockito.mock(BinaryPredicate.class);
    Mockito.when(mock.test()).thenReturn(true);
    BinaryPredicate.Combiner combiner = Mockito.mock(BinaryPredicate.Combiner.class);
    Mockito.when(
            combiner.get(
                ArgumentMatchers.any(Predicate.class), ArgumentMatchers.any(Predicate.class)))
        .thenReturn(mock);
    BinaryExpression expression = new BinaryExpression(null, PredicateExpression.TRUE, combiner);
    expression.evaluate(StaticValueRefResolver.self(this));

    Mockito.verify(combiner, VerificationModeFactory.only())
        .get(ArgumentMatchers.eq(Predicate.FALSE), ArgumentMatchers.eq(Predicate.TRUE));
  }

  @Test
  void testRightNull() {
    BinaryPredicate mock = Mockito.mock(BinaryPredicate.class);
    Mockito.when(mock.test()).thenReturn(true);
    BinaryPredicate.Combiner combiner = Mockito.mock(BinaryPredicate.Combiner.class);
    Mockito.when(
            combiner.get(
                ArgumentMatchers.any(Predicate.class), ArgumentMatchers.any(Predicate.class)))
        .thenReturn(mock);
    BinaryExpression expression = new BinaryExpression(PredicateExpression.TRUE, null, combiner);
    expression.evaluate(StaticValueRefResolver.self(this));

    Mockito.verify(combiner, VerificationModeFactory.only())
        .get(ArgumentMatchers.eq(Predicate.TRUE), ArgumentMatchers.eq(Predicate.FALSE));
  }
}
