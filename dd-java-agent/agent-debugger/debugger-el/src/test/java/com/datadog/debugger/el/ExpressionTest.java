package com.datadog.debugger.el;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExpressionTest {
  @ParameterizedTest
  @MethodSource("literalExpressions")
  void testLiteralExpressions(Literal<?> literal, Object expectedValue) {
    Value<?> value1 = literal.evaluate(null);
    Value<?> value2 = literal.evaluate(new CapturedContext());

    assertNotNull(value1);
    assertNotNull(value2);
    assertEquals(value1, value2);

    assertEquals(expectedValue, value1.getValue());
    assertEquals(expectedValue, value2.getValue());
  }

  private static Stream<Arguments> literalExpressions() {
    return Stream.of(
        Arguments.of(new BooleanValue(true, ValueType.BOOLEAN), true),
        Arguments.of(new NumericValue(15.8d, ValueType.DOUBLE), 15.8d),
        Arguments.of(new StringValue("Hello world"), "Hello world"));
  }

  @Test
  void testPredicateExpression() {
    StringValue string = new StringValue("Hello World");
    StringValue emptyString = new StringValue("");

    ValueReferenceResolver resolver = new CapturedContext();
    IsEmptyExpression isEmpty1 = new IsEmptyExpression(string);
    IsEmptyExpression isEmpty2 = new IsEmptyExpression(emptyString);

    assertFalse(isEmpty1.evaluate(resolver));
    assertTrue(isEmpty2.evaluate(resolver));

    assertTrue(not(isEmpty1).evaluate(resolver));
    assertTrue(or(isEmpty1, isEmpty2).evaluate(resolver));
    assertFalse(and(isEmpty1, isEmpty2).evaluate(resolver));
  }

  @Test
  void testPrettyPrint() {
    assertEquals("\"foo\" == \"bar\"", print(eq(value("foo"), value("bar"))));
    assertEquals("1 == 1", print(eq(value(1), value(1))));
    assertEquals("1 > 1", print(gt(value(1), value(1))));
    assertEquals("1 >= 1", print(ge(value(1), value(1))));
    assertEquals("1 < 1", print(lt(value(1), value(1))));
    assertEquals("1 <= 1", print(le(value(1), value(1))));
    assertEquals(
        "when(this.strField == \"foo\" && @duration > 0)",
        print(
            when(
                and(
                    eq(getMember(ref("this"), "strField"), value("foo")),
                    gt(ref("@duration"), value(0))))));
    assertEquals(
        "len(list[idx].map)", print(len(getMember(index(ref("list"), ref("idx")), "map"))));
  }
}
