package com.datadog.debugger.el.expressions;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.RefResolverHelper;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import org.junit.jupiter.api.Test;

class MatchesExpressionTest {
  private final ValueReferenceResolver resolver = RefResolverHelper.createResolver(this);

  @Test
  void nullExpression() {
    MatchesExpression expression = new MatchesExpression(null, null);
    assertFalse(expression.evaluate(resolver));
    assertEquals("matches(null, null)", expression.prettyPrint());
  }

  @Test
  void undefinedExpression() {
    MatchesExpression expression =
        new MatchesExpression(DSL.value(Values.UNDEFINED_OBJECT), new StringValue(null));
    assertFalse(expression.evaluate(resolver));
    assertEquals("matches(UNDEFINED, \"null\")", expression.prettyPrint());
  }

  @Test
  void stringExpression() {
    MatchesExpression expression = new MatchesExpression(DSL.value("abc"), new StringValue("abc"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("matches(\"abc\", \"abc\")", expression.prettyPrint());
    expression = new MatchesExpression(DSL.value("abc"), new StringValue("^ab.*"));
    assertTrue(expression.evaluate(resolver));
    assertEquals("matches(\"abc\", \"^ab.*\")", expression.prettyPrint());

    expression = new MatchesExpression(DSL.value("abc"), new StringValue("bc"));
    assertFalse(expression.evaluate(resolver));
    assertEquals("matches(\"abc\", \"bc\")", expression.prettyPrint());
    expression = new MatchesExpression(DSL.value("abc"), new StringValue("[def]+"));
    assertFalse(expression.evaluate(resolver));
    assertEquals("matches(\"abc\", \"[def]+\")", expression.prettyPrint());
  }
}
