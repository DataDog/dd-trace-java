package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.RefResolverHelper;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NotExpressionTest {
  @ParameterizedTest
  @MethodSource("expressions")
  void testNullPredicate(BooleanExpression expression, boolean expected, String prettyPrint) {
    NotExpression expr = new NotExpression(expression);
    assertEquals(expected, expr.evaluate(RefResolverHelper.createResolver(this)));
    assertEquals(prettyPrint, print(expr));
  }

  private static Stream<Arguments> expressions() {
    return Stream.of(
        Arguments.of(null, true, "not(false)"),
        Arguments.of(BooleanExpression.TRUE, false, "not(true)"),
        Arguments.of(BooleanExpression.FALSE, true, "not(false)"));
  }
}
