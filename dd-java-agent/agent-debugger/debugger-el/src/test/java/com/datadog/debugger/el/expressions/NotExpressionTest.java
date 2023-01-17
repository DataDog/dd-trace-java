package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.RefResolverHelper;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NotExpressionTest {
  @ParameterizedTest
  @MethodSource("expressions")
  void testNullPredicate(BooleanExpression expression, boolean expected) {
    assertEquals(
        expected, new NotExpression(expression).evaluate(RefResolverHelper.createResolver(this)));
  }

  private static Stream<Arguments> expressions() {
    return Stream.of(
        Arguments.of(null, true),
        Arguments.of(BooleanExpression.TRUE, false),
        Arguments.of(BooleanExpression.FALSE, true));
  }
}
