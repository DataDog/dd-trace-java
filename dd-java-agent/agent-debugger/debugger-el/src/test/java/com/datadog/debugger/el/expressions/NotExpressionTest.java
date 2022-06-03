package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.StaticValueRefResolver;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NotExpressionTest {
  @ParameterizedTest
  @MethodSource("expressions")
  void testNullPredicate(PredicateExpression expression, boolean expected) {
    assertEquals(
        expected, new NotExpression(expression).evaluate(StaticValueRefResolver.self(this)).test());
  }

  private static Stream<Arguments> expressions() {
    return Stream.of(
        Arguments.of(null, true),
        Arguments.of(PredicateExpression.TRUE, false),
        Arguments.of(PredicateExpression.FALSE, true));
  }
}
