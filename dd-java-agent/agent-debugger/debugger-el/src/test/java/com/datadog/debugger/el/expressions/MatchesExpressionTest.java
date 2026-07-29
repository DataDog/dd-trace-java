package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.EvalContextHelper.createEvalContext;
import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.Values;
import java.net.URI;
import org.junit.jupiter.api.Test;

class MatchesExpressionTest {
  private final EvalContext evalContext = createEvalContext(this);
  // used to ref lookup
  URI uri = URI.create("https://www.datadoghq.com");

  @Test
  void nullExpression() {
    MatchesExpression expression = new MatchesExpression(null, null);
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for null value", exception.getMessage());
    assertEquals("matches(null, null)", print(expression));
  }

  @Test
  void undefinedExpression() {
    MatchesExpression expression =
        new MatchesExpression(DSL.value(Values.UNDEFINED_OBJECT), new StringValue(null));
    EvaluationException exception =
        assertThrows(EvaluationException.class, () -> expression.evaluate(evalContext));
    assertEquals("Cannot evaluate the expression for undefined value", exception.getMessage());
    assertEquals("matches(UNDEFINED, \"null\")", print(expression));
  }

  @Test
  void stringExpression() {
    MatchesExpression expression = new MatchesExpression(DSL.value("abc"), new StringValue("abc"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("matches(\"abc\", \"abc\")", print(expression));
    expression = new MatchesExpression(DSL.value("abc"), new StringValue("^ab.*"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("matches(\"abc\", \"^ab.*\")", print(expression));

    expression = new MatchesExpression(DSL.value("abc"), new StringValue("bc"));
    assertFalse(expression.evaluate(evalContext));
    assertEquals("matches(\"abc\", \"bc\")", print(expression));
    expression = new MatchesExpression(DSL.value("abc"), new StringValue("[def]+"));
    assertFalse(expression.evaluate(evalContext));
    assertEquals("matches(\"abc\", \"[def]+\")", print(expression));
  }

  @Test
  void stringPrimitives() {
    MatchesExpression expression =
        new MatchesExpression(DSL.ref("uri"), new StringValue("^https?://w{3}\\.datadoghq\\.com$"));
    assertTrue(expression.evaluate(evalContext));
    assertEquals("matches(uri, \"^https?://w{3}\\.datadoghq\\.com$\")", print(expression));
  }
}
