package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.net.URI;
import org.junit.jupiter.api.Test;

class StartsWithExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);
  // used to ref lookup
  URI uri = URI.create("https://www.datadoghq.com");

  @Test
  void nullExpression() {
    StartsWithExpression expression = new StartsWithExpression(null, null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("startsWith(null, null)", print(expression));
  }

  @Test
  void undefinedExpression() {
    StartsWithExpression expression =
        new StartsWithExpression(DSL.value(Values.UNDEFINED_OBJECT), new StringValue(null));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(resolver));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("startsWith(UNDEFINED, \"null\")", print(expression));
  }

  @Test
  void stringExpression() {
    StartsWithExpression expression =
        new StartsWithExpression(DSL.value("abc"), new StringValue("ab"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("startsWith(\"abc\", \"ab\")", print(expression));

    expression = new StartsWithExpression(DSL.value("abc"), new StringValue("bc"));
    assertFalse(expression.evaluate(resolver));
    assertEquals("startsWith(\"abc\", \"bc\")", print(expression));
  }

  @Test
  void stringPrimitives() {
    StartsWithExpression expression =
        new StartsWithExpression(DSL.ref("uri"), new StringValue("https"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("startsWith(uri, \"https\")", print(expression));
  }
}
